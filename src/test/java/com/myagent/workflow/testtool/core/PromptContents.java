package com.myagent.workflow.testtool.core;

public record PromptContents(String prompt, String projectName) {
    public static PromptContents buildDefault(){
        return new PromptContents(
                """
                        制作一个游戏合集 HTML 页面。
                        包含至少三个玩法不同的小游戏，通过一个主界面切换访问，且代码层面易于添加新的小游戏。
                        先创建一个版本，有连连看、俄罗斯方块、五子棋（无需人机对战）这三个小游戏，功能需合理且足够游戏体验。
                        页面风格统一，视觉上看起来是一个完整的应用。
                        拆分样式、js、html，代码保持结构清晰。拆分明确步骤完成此次工作
                
                        项目名称：test-programming，所有代码放在 test-programming/ 目录下。
                       """,
                "test-programming"
        );
    }
}
//      "制作一个输入什么就输出什么的程序，无需自测, no need for PROJECT.MD or any complex things。这是一个测试",
