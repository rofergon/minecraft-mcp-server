package dev.yuniko.minecraftmcp.bridge;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ActionCoordinator {
    enum Mode {
        READ_ONLY,
        EXCLUSIVE
    }

    interface ActionTask {
        void run() throws Exception;
    }

    interface FailureHandler {
        void onFailure(Exception error);
    }

    private final ExecutorService exclusiveExecutor;

    ActionCoordinator() {
        this(Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "minecraft-mcp-action-coordinator");
            thread.setDaemon(true);
            return thread;
        }));
    }

    ActionCoordinator(ExecutorService exclusiveExecutor) {
        this.exclusiveExecutor = exclusiveExecutor;
    }

    void dispatch(Mode mode, ActionTask task, FailureHandler failureHandler) {
        if (mode == Mode.READ_ONLY) {
            runSafely(task, failureHandler);
            return;
        }

        exclusiveExecutor.submit(() -> runSafely(task, failureHandler));
    }

    private void runSafely(ActionTask task, FailureHandler failureHandler) {
        try {
            task.run();
        } catch (Exception error) {
            failureHandler.onFailure(error);
        }
    }
}
