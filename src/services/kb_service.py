from __future__ import annotations

from abc import ABC, abstractmethod


class KnowledgeBaseInterface(ABC):
    """知识库抽象接口。

    定义设计作品搜索的统一接口，支持多种后端实现:
    - VectorKBService: 向量数据库 (ChromaDB/Qdrant) + embedding
    - RemoteKBService: 远程知识库 API
    - EmbeddingKBService: yunwu.ai embedding + 本地索引
    """

    @abstractmethod
    async def search_designs(self, query: str, count: int = 10) -> list:
        """搜索与查询匹配的设计作品。

        参数:
            query: 搜索查询字符串
            count: 返回结果数量上限，默认10

        返回:
            DesignWork 对象列表
        """
        pass


class KnowledgeBaseService(KnowledgeBaseInterface):
    """知识库服务（当前为占位实现）。

    当前实现返回空列表，不影响主功能流程。
    后续将接入向量数据库实现完整搜索能力。

    未来实现方案:
        1. LLM关键词提取: 使用 LLM 从用户查询中提取设计相关关键词
           (如风格、色调、空间类型、材质等)
        2. 向量搜索: 将关键词转换为 embedding 向量，
           在向量数据库中搜索语义相似的设计作品
        3. 返回DesignWork列表: 将搜索结果映射为 DesignWork 对象列表，
           包含 title, image_path, description, tags 等字段

    示例流程:
        用户输入 "现代简约风格客厅" 
        -> LLM提取关键词 ["现代", "简约", "客厅"]
        -> 向量搜索匹配的摄影作品
        -> 返回 DesignWork 列表 (最多 count 条)
    """

    def __init__(self, config):
        """初始化知识库服务。

        参数:
            config: 配置对象
        """
        self.config = config

    async def search_designs(self, query: str, count: int = 10) -> list:
        """搜索设计作品（当前返回空列表）。

        参数:
            query: 搜索查询字符串
            count: 返回结果数量上限，默认10

        返回:
            当前返回空列表 []
        """
        # TODO: 接入向量数据库实现完整搜索流程
        # 1. 调用 LLM 从 query 中提取设计关键词
        # 2. 使用 embedding 进行向量相似度搜索
        # 3. 将结果映射为 DesignWork 对象列表返回
        return []
