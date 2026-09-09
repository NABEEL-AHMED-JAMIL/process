package process.model.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import process.model.pojo.BenchmarkResult;
import java.util.List;

/**
 * @author Nabeel Ahmed
 * */
@Repository
public interface BenchmarkResultRepository extends JpaRepository<BenchmarkResult, Long> {

    // Every finder takes a Pageable and none has an unbounded variant, the same call
    // AnalyticsQueryRunRepository made: nothing prunes this table, so it is the reads that have to
    // stay bounded. A batch is at most a handful of rows by construction, but giving it the same
    // shape as the others is cheaper than an exception a later reader has to re-derive.
    //
    // Ordered by date so the read matches idx_analytics_benchmark_result_tenant_date, with the id
    // as tie-break because a batch writes its rows inside one millisecond. All three are unscoped
    // by themselves: the caller enables the tenant filter first.
    List<BenchmarkResult> findAllByOrderByDateCreatedDescAnalyticsBenchmarkResultIdDesc(Pageable pageable);

    // One comparison. The rows written together by one invocation are the only rows that may
    // honestly be compared with each other, so this is the finder a results screen leads with --
    // and it is ASCENDING by id, because the order they were measured in is information: a batch
    // whose later rows are all faster measured a warming cache, not a format.
    List<BenchmarkResult> findByBatchIdOrderByAnalyticsBenchmarkResultIdAsc(String batchId, Pageable pageable);

    // Every measurement anybody labelled the same way -- the same dataset at two sizes, or the
    // same comparison re-run after a deployment. Across batches on purpose, which is exactly why
    // batch_id is on every row: this listing is the one where a reader has to check it.
    List<BenchmarkResult> findByBenchmarkLabelOrderByDateCreatedDescAnalyticsBenchmarkResultIdDesc(
        String benchmarkLabel, Pageable pageable);

}
