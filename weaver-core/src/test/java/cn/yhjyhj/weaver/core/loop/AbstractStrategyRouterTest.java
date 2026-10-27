package cn.yhjyhj.weaver.core.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import cn.yhjyhj.weaver.core.loop.chain.AbstractStrategyRouter;
import cn.yhjyhj.weaver.core.loop.chain.StrategyHandler;
import org.junit.jupiter.api.Test;

class AbstractStrategyRouterTest {

    @Test
    void shortCircuitsWithoutRoutingWhenDoApplyReturnsResult() throws Exception {
        ProbeRouter router = new ProbeRouter("handled", (request, context) -> "next");

        String result = router.apply("request", "context");

        assertSame("handled", result);
        assertEquals(1, router.doApplyCalls);
        assertEquals(0, router.getNextCalls);
    }

    @Test
    void routesToNextHandlerWhenDoApplyReturnsNull() throws Exception {
        ProbeRouter router = new ProbeRouter(null, (request, context) -> "next");

        String result = router.apply("request", "context");

        assertEquals("next", result);
        assertEquals(1, router.doApplyCalls);
        assertEquals(1, router.getNextCalls);
    }

    private static final class ProbeRouter
            extends AbstractStrategyRouter<String, String, String> {

        private final String result;
        private final StrategyHandler<String, String, String> next;
        private int doApplyCalls;
        private int getNextCalls;

        private ProbeRouter(String result, StrategyHandler<String, String, String> next) {
            this.result = result;
            this.next = next;
        }

        @Override
        protected String doApply(String request, String context) {
            doApplyCalls++;
            return result;
        }

        @Override
        public StrategyHandler<String, String, String> getNext(String request, String context) {
            getNextCalls++;
            return next;
        }
    }
}
