package io.semanticmap.platform.analysis;

import java.util.function.BooleanSupplier;

public interface TaskHandler {
    String type();

    Outcome execute(TaskLease lease, BooleanSupplier cancelled) throws Exception;

    record Outcome(Runnable persist, Cleanup abandoned) {
        public Outcome(Runnable persist) {
            this(persist, () -> {});
        }
    }

    @FunctionalInterface
    interface Cleanup {
        void run() throws Exception;
    }
}
