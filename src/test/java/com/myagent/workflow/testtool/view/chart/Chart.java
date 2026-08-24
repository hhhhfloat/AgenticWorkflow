package com.myagent.workflow.testtool.view.chart;

import com.myagent.workflow.testtool.model.ChartData;
import javafx.scene.Node;

/**
 * 图表实体抽象类 —— 所有图表类型的基类
 * 定义了图表的基本行为，每个具体图表实现自己的渲染逻辑
 */
public abstract class Chart {

    protected String title;
    protected ChartData data;

    public Chart(String title, ChartData data) {
        this.title = title;
        this.data = data;
    }

    /**
     * 渲染图表，返回 JavaFX Node（可以是 Chart 或 Pane）
     */
    public abstract Node render();

    /**
     * 更新图表数据
     */
    public abstract void update(ChartData newData);

    /**
     * 获取图表标题
     */
    public String getTitle() { return title; }

    /**
     * 获取图表数据
     */
    public ChartData getData() { return data; }

    /**
     * 设置图表标题
     */
    public void setTitle(String title) { this.title = title; }

    /**
     * 设置图表数据
     */
    public void setData(ChartData data) { this.data = data; }

    /**
     * 导出为图片（默认实现，子类可覆盖）
     */
    public void export(String filePath) {
        System.out.println("导出功能未实现: " + getClass().getSimpleName());
    }
}