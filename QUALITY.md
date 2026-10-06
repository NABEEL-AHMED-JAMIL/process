# Code quality (MIG-213)

One standard for every platform repository, and the build enforces it.

## What the build runs

- **Checkstyle** (`config/checkstyle/checkstyle.xml`, the same file in every repository): the
  `platform-code-standard` profile runs it on main and test sources in the `validate` phase, and one
  violation fails the build. The profile switches itself on when that file exists, which is every checkout;
  the image copies a jar built beforehand, so it changes nothing there.
- **javac** with `-Xlint:all,-serial,-try,-processing,-classfile` and `-Werror` (release 17): a compiler
  warning fails the build.

## Left out, and why

- `-serial`, `-try`, `-processing`, `-classfile`: as in the other repositories (see ai-service's QUALITY.md).
- Checkstyle does not check import order, line length or brace placement of one-line methods.

## Deprecated APIs kept on purpose (Wave 6)

Each is suppressed where it is used, with its reason beside it; moving off it changes behaviour and needs its
own tests, so it is Wave 6 work ("Maintainability" and "Security review"), not a lint fix:

- `WebSecurityConfigurerAdapter` (`SecurityConfig`) -- the move to a `SecurityFilterChain` bean.
- `CronSequenceGenerator` (`CronSchedule`) -- `CronExpression` differs around DST and day-of-week.
- `CSVFormat.with*` (`FileFormats`) -- the builder API.
- `DescribeTopicsResult.values()` (`KafkaConnectionProfileServiceImpl`) -- needs a newer Kafka client.
- `EnumConverter` (`SourceTaskServiceImpl`) -- the legacy task screens.

Tests that drive those same APIs carry the same suppression with the same reason.

## SpotBugs

Run by hand, not in the build: the SpotBugs release in the offline Maven cache (4.7.3) cannot read the class
files of the JDK 25 runtime Maven runs on here, so it runs under JDK 17:

```
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -o test-compile \
  com.github.spotbugs:spotbugs-maven-plugin:4.7.3.0:check -Dspotbugs.effort=Max \
  -Dspotbugs.threshold=Medium -Dspotbugs.excludeFilterFile=config/spotbugs/exclude.xml -Dspotbugs.maxHeap=2048
```

It reports nothing. Every finding that is not a bug is in `config/spotbugs/exclude.xml` with its reason.

Fixed in MIG-213:
- `ConfigurationMakerRequest.TagInfo.compareTo` compared the `Long` references, so equal payload ids above 127
  were never equal (test: `ConfigurationMakerRequestTagInfoTest`).
- `EncryptionUtil` draws every IV from one `SecureRandom` instead of making one per call.
- The public constants of `ProcessUtil` and `ProcessTimeUtil` made `final`, their lists and map read-only.
- `PagingUtil.applyPaging*` named like methods; `PipelineDefinition.Settings.SENSITIVITIES` read-only.
- Dead code: two private overloads nothing called, an unused local in `TextChunker`.
- Compiler: raw `FileUploadDto` and `List` types given their type arguments, redundant casts dropped,
  `StringUtils.isEmpty(Object)` replaced by `hasLength` (the values are Strings), `TransactionSynchronization`
  implemented directly instead of the deprecated adapter.

The checkstyle findings on first run (about 1,400: members without a blank line between them, statements sharing
a line, one-line blocks, star imports, spacing) were fixed by layout only. The suite has the same 2,436 tests.
