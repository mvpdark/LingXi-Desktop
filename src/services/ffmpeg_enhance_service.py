"""FFmpeg 画质增强服务，基于 ffmpeg + 硬件加速 (GPU) 自动检测实现图像分辨率提升与增强。

启动时（首次使用时懒加载）自动检测可用 GPU / 硬件加速方法，并选择最优策略：
  - NVIDIA CUDA   : -hwaccel cuda   + scale_cuda
  - Intel QSV     : -hwaccel qsv    + scale_qsv
  - AMD AMF/D3D11VA: -hwaccel d3d11va + scale
  - Apple VideoToolbox: -hwaccel videotoolbox + scale
  - CPU (回退)     : scale=...:flags=lanczos + unsharp

支持四种增强模式：super_resolution / sharpen / denoise / color_enhance。

与 EnhanceService / RembgService 一致，返回
{"success": True, "images": [{"bytes": ..., "format": "png"}]}，
base64 编码由 API 层处理。
"""
from __future__ import annotations

import asyncio
import logging
import sys

logger = logging.getLogger(__name__)

# ffmpeg 可执行文件（默认在 PATH 中查找）
FFMPEG_BIN = "ffmpeg"

# 支持的增强模式
ENHANCE_MODES = {"super_resolution", "sharpen", "denoise", "color_enhance"}


class FFmpegEnhanceService:
    """基于 ffmpeg 的图像增强服务，自动检测 GPU 并选择最优硬件加速策略。

    通过 stdin/stdout 管道传入图像字节并取回增强后的 PNG 字节，
    使用 asyncio.Semaphore(2) 限制并发 ffmpeg 进程数。
    """

    def __init__(self, enabled: bool = True):
        self.enabled = enabled
        # 限制并发 ffmpeg 进程数
        self._semaphore = asyncio.Semaphore(2)
        # GPU 检测结果缓存（懒加载，带锁，只执行一次）
        self._gpu_lock = asyncio.Lock()
        self._gpu_info: dict | None = None

    # ------------------------------------------------------------------
    # GPU 检测
    # ------------------------------------------------------------------

    async def _ensure_gpu(self) -> dict:
        """懒加载 GPU 检测结果（带锁，整个进程生命周期只检测一次）。"""
        if self._gpu_info is not None:
            return self._gpu_info
        async with self._gpu_lock:
            # double-check：拿到锁后再确认一次，避免并发重复检测
            if self._gpu_info is not None:
                return self._gpu_info
            self._gpu_info = await self._detect_gpu()
            info = self._gpu_info
            if not info.get("ffmpeg_available", True):
                logger.error("未检测到 ffmpeg，FFmpeg 增强服务不可用")
            else:
                logger.info(
                    "GPU 检测完成: 类型=%s, 名称=%s, hwaccel=%s, scale_filter=%s",
                    info.get("type"),
                    info.get("name"),
                    info.get("hwaccel"),
                    info.get("scale_filter"),
                )
                logger.info("已选择增强策略: %s", info.get("name"))
            return self._gpu_info

    async def get_gpu_info(self) -> dict:
        """获取 GPU 检测信息（公开接口，供 API 调用）。"""
        return await self._ensure_gpu()

    async def _detect_gpu(self) -> dict:
        """Detect available GPU and hardware acceleration."""
        result: dict = {
            "type": "cpu",
            "hwaccel": None,
            "scale_filter": "scale",
            "name": "CPU",
            "ffmpeg_available": True,
        }

        # 运行 ffmpeg -hwaccels 获取可用的硬件加速方法
        try:
            proc = await asyncio.create_subprocess_exec(
                FFMPEG_BIN,
                "-hwaccels",
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
            )
            stdout, _ = await proc.communicate()
            hwaccels = stdout.decode("utf-8", errors="ignore").lower()
        except FileNotFoundError:
            result["ffmpeg_available"] = False
            logger.error("ffmpeg 未安装或不在 PATH 中，无法进行 GPU 检测")
            return result

        # 补充：从 Windows 注册表读取 GPU 适配器名称（best-effort）
        registry_gpu = ""
        if sys.platform.startswith("win"):
            registry_gpu = await asyncio.to_thread(self._detect_gpu_name_via_registry)
            if registry_gpu:
                logger.info("注册表检测到 GPU 适配器: %s", registry_gpu)

        # Check for NVIDIA CUDA
        if "cuda" in hwaccels:
            # 通过 nvidia-smi 校验并获取显卡名称
            try:
                nvidia_proc = await asyncio.create_subprocess_exec(
                    "nvidia-smi",
                    "--query-gpu=name",
                    "--format=csv,noheader",
                    stdout=asyncio.subprocess.PIPE,
                    stderr=asyncio.subprocess.PIPE,
                )
                nvidia_out, _ = await nvidia_proc.communicate()
                if nvidia_proc.returncode == 0:
                    raw = nvidia_out.decode("utf-8", errors="ignore").strip()
                    gpu_name = raw.splitlines()[0] if raw else "NVIDIA GPU"
                    return {
                        "type": "nvidia",
                        "hwaccel": "cuda",
                        "scale_filter": "scale_cuda",
                        "name": gpu_name,
                        "ffmpeg_available": True,
                    }
            except FileNotFoundError:
                pass
            # nvidia-smi 不可用但 hwaccels 支持 cuda，仍尝试使用 CUDA 策略
            logger.info("hwaccels 含 cuda，但 nvidia-smi 不可用；尝试使用 CUDA 策略")
            return {
                "type": "nvidia",
                "hwaccel": "cuda",
                "scale_filter": "scale_cuda",
                "name": registry_gpu or "NVIDIA CUDA",
                "ffmpeg_available": True,
            }

        # Check for Intel QSV
        if "qsv" in hwaccels:
            return {
                "type": "intel",
                "hwaccel": "qsv",
                "scale_filter": "scale_qsv",
                "name": registry_gpu or "Intel QuickSync",
                "ffmpeg_available": True,
            }

        # Check for AMD AMF
        if "amf" in hwaccels:
            return {
                "type": "amd",
                "hwaccel": "d3d11va" if "d3d11va" in hwaccels else None,
                "scale_filter": "scale",
                "name": registry_gpu or "AMD AMF",
                "ffmpeg_available": True,
            }

        # Check for D3D11VA (AMD or other)
        if "d3d11va" in hwaccels:
            return {
                "type": "amd",
                "hwaccel": "d3d11va",
                "scale_filter": "scale",
                "name": registry_gpu or "D3D11VA",
                "ffmpeg_available": True,
            }

        # Check for VideoToolbox (macOS)
        if "videotoolbox" in hwaccels:
            return {
                "type": "apple",
                "hwaccel": "videotoolbox",
                "scale_filter": "scale",
                "name": "Apple VideoToolbox",
                "ffmpeg_available": True,
            }

        # 无可用硬件加速，回退 CPU
        if registry_gpu:
            result["name"] = registry_gpu
        return result

    @staticmethod
    def _detect_gpu_name_via_registry() -> str:
        """通过 Windows 注册表读取 GPU 适配器名称（best-effort，同步）。

        读取显示适配器设备类下各子项的 DriverDesc，返回逗号分隔的名称字符串。
        任何异常都静默忽略，返回空字符串。
        """
        try:
            import winreg

            base = (
                r"SYSTEM\CurrentControlSet\Control\Class"
                r"\{4d36e968-e325-11ce-bfc1-08002be10318}"
            )
            names: list[str] = []
            with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, base) as cls_key:
                index = 0
                while True:
                    try:
                        subkey_name = winreg.EnumKey(cls_key, index)
                    except OSError:
                        break
                    index += 1
                    try:
                        with winreg.OpenKey(cls_key, subkey_name) as sub:
                            desc, _ = winreg.QueryValueEx(sub, "DriverDesc")
                            if desc:
                                names.append(str(desc))
                    except OSError:
                        continue
            return ", ".join(names) if names else ""
        except Exception:
            return ""

    # ------------------------------------------------------------------
    # 命令构建
    # ------------------------------------------------------------------

    def _build_command(self, gpu: dict, mode: str, scale: int) -> list[str]:
        """根据 GPU 信息与增强模式构建 ffmpeg 命令。

        super_resolution 使用硬件加速解码 + GPU 缩放滤镜；
        其他模式使用纯 CPU 滤镜（与文档示例一致）。
        """
        hwaccel = gpu.get("hwaccel")
        scale_filter = gpu.get("scale_filter", "scale")
        gpu_type = gpu.get("type", "cpu")

        # 公共前缀：-y 覆盖输出，-hide_banner -loglevel error 抑制冗余输出
        cmd: list[str] = [FFMPEG_BIN, "-y", "-hide_banner", "-loglevel", "error"]

        if mode == "super_resolution":
            # 硬件加速仅用于放大场景
            if hwaccel:
                cmd += ["-hwaccel", hwaccel]
            cmd += ["-i", "pipe:0"]
            if scale_filter == "scale_cuda":
                # NVIDIA: ffmpeg -hwaccel cuda -i pipe:0 -vf "scale_cuda=iw*2:ih*2" ...
                vf = f"scale_cuda=iw*{scale}:ih*{scale}"
            elif scale_filter == "scale_qsv":
                # Intel: scale_qsv=iw*2:ih*2
                vf = f"scale_qsv=iw*{scale}:ih*{scale}"
            else:
                # d3d11va / videotoolbox / cpu
                vf = f"scale=iw*{scale}:ih*{scale}:flags=lanczos"
                if gpu_type == "cpu":
                    # CPU 回退：lanczos 放大 + unsharp 锐化
                    vf += ",unsharp=5:5:1.0"
            cmd += ["-vf", vf, "-f", "image2pipe", "-vcodec", "png", "pipe:1"]
        else:
            # 其他模式：纯 CPU 滤镜
            cmd += ["-i", "pipe:0"]
            if mode == "sharpen":
                # ffmpeg -i pipe:0 -vf "unsharp=5:5:1.0:5:5:0.0" ...
                vf = "unsharp=5:5:1.0:5:5:0.0"
            elif mode == "denoise":
                # ffmpeg -i pipe:0 -vf "hqdn3d=4:3:6:4" ...
                vf = "hqdn3d=4:3:6:4"
            elif mode == "color_enhance":
                # ffmpeg -i pipe:0 -vf "eq=brightness=0.05:contrast=1.1:saturation=1.2" ...
                vf = "eq=brightness=0.05:contrast=1.1:saturation=1.2"
            else:
                vf = f"scale=iw*{scale}:ih*{scale}:flags=lanczos"
            cmd += ["-vf", vf, "-f", "image2pipe", "-vcodec", "png", "pipe:1"]

        return cmd

    # ------------------------------------------------------------------
    # 执行
    # ------------------------------------------------------------------

    async def _run_ffmpeg(self, command: list[str], image_bytes: bytes) -> bytes | None:
        """执行 ffmpeg 命令，输入图像字节，返回输出图像字节；失败返回 None。"""
        proc = await asyncio.create_subprocess_exec(
            *command,
            stdin=asyncio.subprocess.PIPE,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
        stdout, stderr = await proc.communicate(input=image_bytes)
        if proc.returncode == 0 and stdout:
            return stdout
        err = stderr.decode("utf-8", errors="ignore").strip() if stderr else ""
        logger.error("ffmpeg 执行失败 (returncode=%s): %s", proc.returncode, err or "<无输出>")
        logger.error("执行命令: %s", " ".join(command))
        return None

    # ------------------------------------------------------------------
    # 对外接口
    # ------------------------------------------------------------------

    async def enhance(self, image_bytes: bytes, mode: str = "super_resolution", scale: int = 2) -> dict:
        """对图像执行增强处理。

        Args:
            image_bytes: 输入图像字节（PNG/JPG 等 ffmpeg 可解码格式）。
            mode: 增强模式，可选 super_resolution / sharpen / denoise / color_enhance。
            scale: 放大倍数（仅 super_resolution 生效，支持 2 或 4）。

        Returns:
            成功: {"success": True, "images": [{"bytes": <bytes>, "format": "png"}]}
            失败: {"success": False, "error": "<信息>"}
        """
        if not self.enabled:
            return {"success": False, "error": "FFmpeg 增强服务未启用"}

        if not image_bytes:
            return {"success": False, "error": "输入图像为空"}

        if mode not in ENHANCE_MODES:
            return {"success": False, "error": f"不支持的增强模式: {mode}"}

        if mode == "super_resolution" and scale not in (2, 4):
            logger.warning("不支持的放大倍数 %s，回退到 2x", scale)
            scale = 2

        # 懒加载 GPU 检测
        gpu = await self._ensure_gpu()
        if not gpu.get("ffmpeg_available", True):
            return {
                "success": False,
                "error": "ffmpeg 未安装或不在 PATH 中。请安装 ffmpeg 后重试。",
            }

        command = self._build_command(gpu, mode, scale)
        logger.info(
            "执行增强: mode=%s, scale=%s, 策略=%s (%s)",
            mode,
            scale,
            gpu.get("name"),
            gpu.get("scale_filter"),
        )

        try:
            async with self._semaphore:
                output = await self._run_ffmpeg(command, image_bytes)

            if output is not None:
                return {"success": True, "images": [{"bytes": output, "format": "png"}]}

            # GPU 特定缩放滤镜失败时，回退到 CPU (lanczos + unsharp) 策略重试一次
            if gpu.get("type") != "cpu" and mode == "super_resolution":
                logger.warning("GPU 缩放滤镜执行失败，回退到 CPU (lanczos + unsharp) 策略重试")
                cpu_gpu = {
                    "type": "cpu",
                    "hwaccel": None,
                    "scale_filter": "scale",
                    "name": "CPU (fallback)",
                    "ffmpeg_available": True,
                }
                cpu_command = self._build_command(cpu_gpu, mode, scale)
                async with self._semaphore:
                    output = await self._run_ffmpeg(cpu_command, image_bytes)
                if output is not None:
                    return {"success": True, "images": [{"bytes": output, "format": "png"}]}

            return {
                "success": False,
                "error": "ffmpeg 图像增强失败，请检查 ffmpeg 是否支持所需滤镜",
            }
        except FileNotFoundError:
            logger.error("ffmpeg 未找到 (FileNotFoundError)")
            return {
                "success": False,
                "error": "ffmpeg 未安装或不在 PATH 中。请安装 ffmpeg 后重试。",
            }
        except Exception as ex:
            logger.error("FFmpeg 增强异常: %s", ex, exc_info=True)
            return {"success": False, "error": f"FFmpeg 增强异常: {ex}"}
