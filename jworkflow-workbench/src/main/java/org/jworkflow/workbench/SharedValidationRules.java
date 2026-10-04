package org.jworkflow.workbench;

/** Integer-only rules compiled equivalently into the bundled browser Wasm module. */
public final class SharedValidationRules {
    public static final int REQUIRED = 1;
    public static final int MAX_BLOCKS = 2;
    public static final int MAX_DEPTH = 3;
    private SharedValidationRules() {}
    public static int check(int rule, int value) {
        return switch (rule) {
            case REQUIRED -> value == 0 ? 1001 : 0;
            case MAX_BLOCKS -> value > WorkflowGenerationService.MAX_BLOCKS ? 1002 : 0;
            case MAX_DEPTH -> value > WorkflowGenerationService.MAX_DEPTH ? 1003 : 0;
            default -> 1099;
        };
    }
}
