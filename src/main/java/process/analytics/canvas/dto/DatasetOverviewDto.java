package process.analytics.canvas.dto;

import process.analytics.canvas.AnalysisRequest;
import process.analytics.dto.ColumnDistributionDto;
import process.analytics.dto.DatasetProfileDto;

import java.util.ArrayList;
import java.util.List;

/**
 * A dataset at a glance: the profile it was read with, and a handful of charts chosen from what
 * its columns actually are -- rows over time when there is a date, the top values of the
 * categorical columns, the spread of the numeric ones. Every chart carries the request that
 * produced it, so the Canvas can pick it up unchanged.
 *
 * @author Nabeel Ahmed
 */
public class DatasetOverviewDto {

    /** One chart on the overview: what it asks, what was asked, and what came back. */
    public static class Chart {
        /** rowsOverTime · topValues · spread · completeness -- what the screen draws it as. */
        private String kind;
        private String title;
        /** The question in a sentence, for the tile's description. */
        private String question;
        private String column;
        private AnalysisRequest request;
        private AnalysisResultDto result;
        /** For the spread of a numeric column, the bins the engine drew. */
        private ColumnDistributionDto distribution;
        /** Set instead of a result when the engine refused or failed this one chart. */
        private String error;

        public String getKind() { return this.kind; }
        public void setKind(String kind) { this.kind = kind; }
        public String getTitle() { return this.title; }
        public void setTitle(String title) { this.title = title; }
        public String getQuestion() { return this.question; }
        public void setQuestion(String question) { this.question = question; }
        public String getColumn() { return this.column; }
        public void setColumn(String column) { this.column = column; }
        public AnalysisRequest getRequest() { return this.request; }
        public void setRequest(AnalysisRequest request) { this.request = request; }
        public AnalysisResultDto getResult() { return this.result; }
        public void setResult(AnalysisResultDto result) { this.result = result; }
        public ColumnDistributionDto getDistribution() { return this.distribution; }
        public void setDistribution(ColumnDistributionDto distribution) { this.distribution = distribution; }
        public String getError() { return this.error; }
        public void setError(String error) { this.error = error; }
    }

    private DatasetProfileDto profile;
    private List<Chart> charts = new ArrayList<>();
    private long durationMs;

    public DatasetProfileDto getProfile() { return this.profile; }
    public void setProfile(DatasetProfileDto profile) { this.profile = profile; }
    public List<Chart> getCharts() { return this.charts; }
    public void setCharts(List<Chart> charts) { this.charts = charts; }
    public long getDurationMs() { return this.durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }
}
