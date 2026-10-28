package cn.yhjyhj.weaver.core.loop.intent;

/** 意图分类 SPI；M1 用规则，后续可替换为模型分类。 */
@FunctionalInterface
public interface IntentClassifier {

    /**
     * 根据用户输入判断本轮意图。
     *
     * @param input 本轮用户输入，可为 {@code null}
     * @return 分类结果
     */
    Intent classify(String input);
}
