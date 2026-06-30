package com.hify.provider.api;

import com.hify.provider.api.dto.ModelView;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * provider 模块对外门面（一个模块最多一个 Facade，受 Modulith 强制校验）。
 * 其他模块只能经本接口与 provider 同步交互；签名只用 api/dto + JDK 类型
 * （provider 例外允许 Spring AI 类型，留待 C2 的 getChatClient）。
 *
 * <p>C1 只暴露读侧校验；C2 将新增 {@code getChatClient(modelId)} / {@code getEmbeddingModel(modelId)}。
 */
public interface ProviderFacade {

    /**
     * 按 id 取一个「可用」的 chat 模型。可用 = 模型 enabled + type=chat + 所属供应商 enabled。
     * 不存在/停用/非 chat/供应商停用均返回 {@link Optional#empty()}；调用方据空与否决定自身错误码。
     */
    Optional<ModelView> findUsableChatModel(Long modelId);

    /**
     * 批量取模型名映射（id→name），<b>展示用途，不管启停都返回</b>。供 app 列表/详情回显模型名、
     * 编辑弹窗展示已停用模型名。空/null 入参返回空 map。与 {@link #findUsableChatModel} 的「可用」过滤区分。
     */
    Map<Long, String> getModelNames(Collection<Long> modelIds);

    /**
     * 从给定 id 中筛出「可用」的 chat 模型 id（enabled + chat + 供应商 enabled）。
     * 供 app 列表批量标注模型启停状态。空/null 入参返回空集。
     */
    Set<Long> filterUsableChatModelIds(Collection<Long> modelIds);

    /**
     * 取一个「可用」chat 模型的 {@link ChatClient}（自带韧性：call 全四件套 + stream 够用子集（信号量/三层超时/熔断，不重试））。
     * 不可用抛 {@code BizException(ProviderError.MODEL_NOT_USABLE)}。供 conversation / admin 测试调用。
     */
    ChatClient getChatClient(Long modelId);
}
