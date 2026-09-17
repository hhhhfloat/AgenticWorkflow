package com.myagent.workflow.core.newcore;

public final class NewSystemPrompt {

    private NewSystemPrompt(){

    }

    public static String get(WorkStyle workStyle){
        return switch (workStyle) {
            case UNDERSTAND -> promptUnderstand;
            case PLAN -> promptPlan;
            case EXECUTION -> promptExecution;
            case SUMMARY -> promptSummary;
            default -> "UNKNOWN FATAL";
        };
    }

    private static String promptUnderstand = """
            
            """;
    private static String promptPlan = """
            """;
    private static String promptExecution = """
            """;
    private static String promptSummary = """
            """;


}
