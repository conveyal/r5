package com.conveyal.r5.analyst.network;

import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.block.BlockBorder;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.category.BarRenderer;
import org.jfree.chart.title.TextTitle;
import org.jfree.chart.ui.ApplicationFrame;
import org.jfree.chart.ui.UIUtils;
import org.jfree.data.category.CategoryDataset;
import org.jfree.data.category.DefaultCategoryDataset;
import org.jfree.data.time.Minute;
import org.jfree.data.time.TimeSeries;
import org.jfree.data.time.TimeSeriesCollection;

import java.awt.*;
import java.util.Arrays;

/**
 * Show a chart of predicted or empirical distributions in a Swing graphical window.
 * Used in test design and debugging.
 * TODO show percentiles as vertical lines on chart
 */
public class DistributionChart extends ApplicationFrame {

    public DistributionChart (Distribution... distributions) {
        super("Distribution Chart");
        JFreeChart chart = createChart(distributions);
        ChartPanel chartPanel = new ChartPanel(chart, false);
        chartPanel.setFillZoomRectangle(true);
        chartPanel.setMouseWheelEnabled(true);
        chartPanel.setPreferredSize(new Dimension(500, 270));
        setContentPane(chartPanel);

    }

    public static void showChart (Distribution... distributions) {
        DistributionChart chart = new DistributionChart(distributions);
        chart.pack();
        UIUtils.centerFrameOnScreen(chart);
        chart.setVisible(true);
        try {
            Thread.sleep(60*60*1000);
        } catch (InterruptedException e) {
            throw new RuntimeException("Exception:", e);
        }
    }

    public JFreeChart createChart (Distribution... distributions) {
        JFreeChart chart = ChartFactory.createTimeSeriesChart(
                "travel time (minutes)", null,
                "probability mass per minute",
                createTimeSeriesDataset(distributions)
        );
        chart.setBackgroundPaint(Color.WHITE);
        // XYPlot plot = (XYPlot) chart.getPlot();
        return chart;
    }

    private static TimeSeriesCollection createTimeSeriesDataset (Distribution... distributions) {
        TimeSeriesCollection dataset = new TimeSeriesCollection();
        for (Distribution distribution : distributions) {
            TimeSeries ts = new TimeSeries("X");
            for (int i = distribution.skip(); i < distribution.fullWidth(); i++) {
                double p = distribution.probabilityOf(i);
                ts.add(new Minute(i, 0, 1, 1, 2000), p);
            }
            dataset.addSeries(ts);
        }
        return dataset;
    }

}
