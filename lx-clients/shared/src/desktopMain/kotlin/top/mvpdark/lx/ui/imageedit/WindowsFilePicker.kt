package top.mvpdark.lx.ui.imageedit

import com.sun.jna.Function
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import top.mvpdark.lx.core.util.PlatformLogger

/**
 * Windows 原生文件选择器：通过 JNA 调用 COM IFileOpenDialog 接口。
 *
 * 这是 Windows 10/11 的现代文件选择对话框（Fluent Design），
 * 相比 AWT FileDialog（使用旧的 GetOpenFileName Win32 API）：
 * - 圆角 Fluent Design 界面
 * - 支持文件预览、搜索、快速访问、面包屑导航
 * - 与 Windows 11 系统风格完全一致
 *
 * 实现原理：
 * 1. CoInitializeEx 初始化 COM 库
 * 2. CoCreateInstance 创建 IFileOpenDialog COM 对象
 * 3. 通过 vtable 调用 SetOptions / SetFileTypes / SetTitle / Show / GetResult
 * 4. 从 IShellItem::GetDisplayName 获取文件系统路径
 * 5. CoTaskMemFree 释放 COM 分配的字符串内存
 * 6. CoUninitialize 清理 COM
 *
 * vtable 索引参考 MSDN IFileDialog 接口文档：
 * https://learn.microsoft.com/en-us/windows/win32/api/shobjidl_core/nn-shobjidl_core-ifiledialog
 */
internal object WindowsFilePicker {

    /**
     * 自定义 Ole32 接口 — 使用 Pointer 类型避免 Guid.REFCLSID 类型不兼容。
     *
     * 在 64 位 Windows 上，CDECL 和 STDCALL 调用约定等价（只有一种 x64 调用约定）。
     */
    private interface MyOle32 : Library {
        fun CoInitializeEx(pvReserved: Pointer?, dwCoInit: Int): Int
        fun CoCreateInstance(
            rclsid: Pointer,
            pUnkOuter: Pointer?,
            dwClsContext: Int,
            riid: Pointer,
            ppv: PointerByReference,
        ): Int
        fun CoTaskMemFree(p: Pointer)
        fun CoUninitialize()
    }

    private val ole32: MyOle32 by lazy {
        Native.load("ole32.dll", MyOle32::class.java)
    }

    // --- COM 常量 ---
    private const val COINIT_APARTMENTTHREADED = 0x2
    private const val CLSCTX_ALL = 0x17
    private const val S_OK = 0
    private const val S_FALSE = 1
    private const val ERROR_CANCELLED = -2147023673 // 0x800704C7 — 用户取消选择

    // --- File Open Dialog Options ---
    private const val FOS_FORCEFILESYSTEM = 0x00000040
    private const val FOS_FILEMUSTEXIST = 0x00001000

    // --- SIGDN (Shell Item Get Display Name) ---
    // 0x80058000 = 2147844096 (unsigned) → signed int32 = -2147123200
    // 修正：之前错误地使用了 -2147450880 (0x80008000)，导致 GetDisplayName 失败
    private const val SIGDN_FILESYSPATH = -2147123200 // 0x80058000

    // --- IFileOpenDialog vtable 索引 ---
    // IUnknown: 0=QueryInterface, 1=AddRef, 2=Release
    // IModalWindow: 3=Show
    // IFileDialog: 4=SetFileTypes, 5=SetFileTypeIndex, 6=GetFileTypeIndex,
    //   7=Advise, 8=Unadvise, 9=SetOptions, 10=GetOptions,
    //   11=SetDefaultFolder, 12=SetFolder, 13=GetFolder, 14=GetCurrentSelection,
    //   15=SetFileName, 16=GetFileName, 17=SetTitle, 18=SetOkButtonLabel,
    //   19=SetFileNameLabel, 20=GetResult, 21=AddPlace, 22=SetDefaultExtension,
    //   23=Close, 24=SetClientGuid, 25=ClearClientData, 26=SetFilter
    private const val VT_RELEASE = 2
    private const val VT_SHOW = 3
    private const val VT_SET_FILE_TYPES = 4
    private const val VT_SET_FILE_TYPE_INDEX = 5
    private const val VT_SET_OPTIONS = 9
    private const val VT_GET_OPTIONS = 10
    private const val VT_SET_TITLE = 17
    private const val VT_GET_RESULT = 20

    // --- IShellItem vtable 索引 ---
    // IUnknown: 0=QueryInterface, 1=AddRef, 2=Release
    // IShellItem: 3=BindToHandler, 4=GetParent, 5=GetDisplayName,
    //   6=GetAttributes, 7=Compare
    private const val VT_SI_RELEASE = 2
    private const val VT_SI_GET_DISPLAY_NAME = 5

    // --- CLSID_FileOpenDialog = {DC1C5A9C-E88A-4DDE-A5A1-60F82A20AEF7} ---
    private val CLSID_FILE_OPEN_DIALOG = createGuid(
        0xDC1C5A9C.toInt(),
        0xE88A.toShort(),
        0x4DDE.toShort(),
        byteArrayOf(0xA5.toByte(), 0xA1.toByte(), 0x60.toByte(), 0xF8.toByte(), 0x2A.toByte(), 0x20.toByte(), 0xAE.toByte(), 0xF7.toByte()),
    )

    // --- IID_IFileOpenDialog = {D57C7288-D4AD-4768-BE02-9D969532D960} ---
    private val IID_IFILE_OPEN_DIALOG = createGuid(
        0xD57C7288.toInt(),
        0xD4AD.toShort(),
        0x4768.toShort(),
        byteArrayOf(0xBE.toByte(), 0x02.toByte(), 0x9D.toByte(), 0x96.toByte(), 0x95.toByte(), 0x32.toByte(), 0xD9.toByte(), 0x60.toByte()),
    )

    /**
     * 显示 Windows 11 原生文件选择对话框。
     *
     * 必须在 AWT EDT（Event Dispatch Thread）上调用，因为 COM IFileOpenDialog::Show
     * 需要消息泵（Message Pump）来处理对话框的 UI 事件。EDT 是 STA 线程，自带消息泵。
     *
     * @return 选中文件的完整路径，用户取消或出错时返回 null
     */
    fun pickImageFile(): String? {
        PlatformLogger.d("WindowsFilePicker", "pickImageFile() start")
        return try {
            val initHr = ole32.CoInitializeEx(null, COINIT_APARTMENTTHREADED)
            PlatformLogger.d("WindowsFilePicker", "CoInitializeEx hr=0x${initHr.toString(16)}")
            if (initHr != S_OK && initHr != S_FALSE) {
                PlatformLogger.e("WindowsFilePicker", "CoInitializeEx failed: 0x${initHr.toString(16)}")
                return null
            }
            try {
                showDialog()
            } finally {
                if (initHr == S_OK) {
                    ole32.CoUninitialize()
                    PlatformLogger.d("WindowsFilePicker", "CoUninitialize done")
                }
            }
        } catch (e: Throwable) {
            PlatformLogger.e("WindowsFilePicker", "Native picker failed", e)
            null
        }
    }

    private fun showDialog(): String? {
        // CoCreateInstance(IFileOpenDialog)
        val ppv = PointerByReference()
        val hr = ole32.CoCreateInstance(
            CLSID_FILE_OPEN_DIALOG,
            null,
            CLSCTX_ALL,
            IID_IFILE_OPEN_DIALOG,
            ppv,
        )
        PlatformLogger.d("WindowsFilePicker", "CoCreateInstance hr=0x${hr.toString(16)}")
        if (hr != S_OK) {
            PlatformLogger.e("WindowsFilePicker", "CoCreateInstance failed: 0x${hr.toString(16)}")
            return null
        }

        val pDialog = ppv.value
        val vtable = pDialog.getPointer(0)

        try {
            // GetOptions → 追加 FOS_FORCEFILESYSTEM | FOS_FILEMUSTEXIST → SetOptions
            val optsRef = IntByReference()
            invokeCom(vtable, VT_GET_OPTIONS, pDialog, optsRef)
            val newOpts = optsRef.value or FOS_FORCEFILESYSTEM or FOS_FILEMUSTEXIST
            val setOptsHr = invokeCom(vtable, VT_SET_OPTIONS, pDialog, newOpts)
            PlatformLogger.d("WindowsFilePicker", "SetOptions hr=0x${setOptsHr.toString(16)}, opts=0x${newOpts.toString(16)}")

            // SetFileTypes — 图片过滤器
            val filterMem = allocFilterSpecs(
                listOf(
                    "图片文件" to "*.jpg;*.jpeg;*.png;*.webp;*.gif;*.bmp",
                    "所有文件" to "*.*",
                ),
            )
            val setFtHr = invokeCom(vtable, VT_SET_FILE_TYPES, pDialog, 2, filterMem)
            PlatformLogger.d("WindowsFilePicker", "SetFileTypes hr=0x${setFtHr.toString(16)}")

            // SetFileTypeIndex — 默认选中第一个过滤器（1-based）
            invokeCom(vtable, VT_SET_FILE_TYPE_INDEX, pDialog, 1)

            // SetTitle
            val titleMem = allocWideString("选择图片")
            invokeCom(vtable, VT_SET_TITLE, pDialog, titleMem)

            // Show(HWND_OWNER = null) — 在 EDT 上调用，有消息泵
            PlatformLogger.d("WindowsFilePicker", "Show() — opening native dialog...")
            val showHr = invokeCom(vtable, VT_SHOW, pDialog, null as Pointer?)
            PlatformLogger.d("WindowsFilePicker", "Show hr=0x${showHr.toString(16)}")
            if (showHr == ERROR_CANCELLED) {
                PlatformLogger.d("WindowsFilePicker", "User cancelled")
                return null // 用户取消
            }
            if (showHr != S_OK) {
                PlatformLogger.e("WindowsFilePicker", "Show failed: 0x${showHr.toString(16)}")
                return null
            }

            // GetResult → IShellItem
            val pShellItemRef = PointerByReference()
            val resultHr = invokeCom(vtable, VT_GET_RESULT, pDialog, pShellItemRef)
            PlatformLogger.d("WindowsFilePicker", "GetResult hr=0x${resultHr.toString(16)}")
            if (resultHr != S_OK) {
                PlatformLogger.e("WindowsFilePicker", "GetResult failed: 0x${resultHr.toString(16)}")
                return null
            }

            val pShellItem = pShellItemRef.value
            val siVtable = pShellItem.getPointer(0)

            try {
                // GetDisplayName(SIGDN_FILESYSPATH) → LPWSTR*
                val nameRef = PointerByReference()
                val nameHr = invokeCom(siVtable, VT_SI_GET_DISPLAY_NAME, pShellItem, SIGDN_FILESYSPATH, nameRef)
                PlatformLogger.d("WindowsFilePicker", "GetDisplayName hr=0x${nameHr.toString(16)}, SIGDN=0x${(SIGDN_FILESYSPATH + 0x100000000).toString(16)}")

                val pathPtr = nameRef.value
                if (pathPtr == null) {
                    PlatformLogger.e("WindowsFilePicker", "GetDisplayName returned null path")
                    return null
                }
                val path = pathPtr.getWideString(0)
                ole32.CoTaskMemFree(pathPtr)

                PlatformLogger.d("WindowsFilePicker", "Selected path: $path")
                return path
            } finally {
                invokeCom(siVtable, VT_SI_RELEASE, pShellItem)
            }
        } finally {
            invokeCom(vtable, VT_RELEASE, pDialog)
        }
    }

    /**
     * 调用 COM vtable 方法。
     *
     * COM 对象内存布局：对象指针 → vtable 指针 → 函数指针数组。
     * 每个方法的第一个隐式参数是 this 指针（COM 对象本身）。
     *
     * 64 位 Windows 上只有一种调用约定（Microsoft x64），
     * 因此 Function.getFunction 默认约定即可正确调用 COM 方法。
     */
    private fun invokeCom(vtable: Pointer, index: Int, thisPtr: Pointer, vararg args: Any?): Int {
        val funcPtr = vtable.getPointer(index.toLong() * Native.POINTER_SIZE)
            ?: error("vtable[$index] returned null function pointer")
        val func = Function.getFunction(funcPtr)
        val allArgs = arrayOf<Any?>(thisPtr, *args)
        return func.invokeInt(allArgs)
    }

    /**
     * 创建 GUID 结构体（16 字节）到 Native 内存。
     *
     * GUID = { DWORD Data1; WORD Data2; WORD Data3; BYTE Data4[8]; }
     */
    private fun createGuid(data1: Int, data2: Short, data3: Short, data4: ByteArray): Memory {
        val mem = Memory(16)
        mem.setInt(0, data1)
        mem.setShort(4, data2)
        mem.setShort(6, data3)
        mem.write(8, data4, 0, 8)
        return mem
    }

    /**
     * 分配宽字符串（UTF-16LE + null 终止符）到 Native 内存。
     */
    private fun allocWideString(str: String): Memory {
        val mem = Memory((str.length + 1) * 2L)
        mem.setWideString(0, str)
        return mem
    }

    /**
     * 分配 COMDLG_FILTERSPEC 结构体数组到单个 Memory 块。
     *
     * 结构体布局（64位）：
     * ```
     * offset 0:  pszName → 指向 name 宽字符串
     * offset 8:  pszSpec → 指向 spec 宽字符串
     * ```
     *
     * 所有字符串数据紧跟在结构体数组之后，
     * 避免单独分配的 Memory 被 GC 回收导致悬垂指针。
     */
    private fun allocFilterSpecs(filters: List<Pair<String, String>>): Memory {
        val count = filters.size
        val ptrSize = Native.POINTER_SIZE.toLong()
        val structSize = count * 2L * ptrSize

        // 计算字符串区域总大小
        var stringAreaSize = 0L
        for ((name, spec) in filters) {
            stringAreaSize += (name.length + 1) * 2L
            stringAreaSize += (spec.length + 1) * 2L
        }

        val mem = Memory(structSize + stringAreaSize)
        var strOffset = structSize

        for (i in filters.indices) {
            val (name, spec) = filters[i]

            // pszName — 指向 name 字符串
            mem.setWideString(strOffset, name)
            mem.setPointer(i * 2L * ptrSize, mem.share(strOffset))
            strOffset += (name.length + 1) * 2L

            // pszSpec — 指向 spec 字符串
            mem.setWideString(strOffset, spec)
            mem.setPointer(i * 2L * ptrSize + ptrSize, mem.share(strOffset))
            strOffset += (spec.length + 1) * 2L
        }

        return mem
    }
}
