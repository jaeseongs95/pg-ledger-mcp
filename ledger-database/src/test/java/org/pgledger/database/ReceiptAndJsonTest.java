package org.pgledger.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.database.repository.Counter;
import org.pgledger.database.repository.HistoryRepository;
import org.pgledger.database.repository.IdAllocator;
import org.pgledger.database.repository.ReceiptCompletionRepository;
import org.pgledger.database.repository.ReceiptRepository;
import org.pgledger.database.support.DatabaseTest;
import org.pgledger.database.support.LedgerTestContext;
import org.pgledger.database.support.Parallel;
import org.pgledger.database.support.Seed;
import org.pgledger.domain.model.Defect;
import org.pgledger.domain.model.HistoryEntry;
import org.pgledger.domain.model.Receipt;
import org.pgledger.domain.model.ReceiptOperation;
import org.pgledger.domain.rule.ActorAssurance;
import org.pgledger.domain.rule.Channel;
import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.rule.HistoryOp;
import org.pgledger.domain.rule.ReceiptOrigin;
import org.pgledger.domain.value.ActorId;
import org.pgledger.domain.value.JsonDocument;
import org.pgledger.domain.value.RequestId;
import org.pgledger.domain.value.Sha256;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** REPO-017, REPO-018, REPO-041 (receipts) and REPO-019 (JSON text columns). */
@DatabaseTest
class ReceiptAndJsonTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private LedgerTestContext ctx;
    private ReceiptRepository receipts;

    @BeforeEach
    void setUp(LedgerTestContext context) {
        ctx = context;
        receipts = ctx.bean(ReceiptRepository.class);
    }

    private Receipt reserve(RequestId id) {
        return Receipt.reserve(id, ReceiptOperation.SAVE, Sha256.of("a".repeat(64)), Seed.ALICE, Channel.AUTHOR,
                LedgerTestContext.START);
    }

    @Test
    void repo017_aSecondInsertOfTheSameRequestIdIsADuplicateKey() {
        RequestId id = new RequestId(UUID.randomUUID());
        ctx.tx(() -> receipts.insert(reserve(id)));
        assertThatThrownBy(() -> ctx.tx(() -> receipts.insert(reserve(id)))).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void repo018_aDuplicateWaitsForTheFirstInserterAndSucceedsAfterItsRollback() throws Exception {
        TransactionTemplate tx = ctx.bean(TransactionTemplate.class);
        RequestId id = new RequestId(UUID.randomUUID());
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<Void> first = Parallel.start(() -> tx.execute(status -> {
            receipts.insert(reserve(id));
            inserted.countDown();
            awaitQuietly(release);
            status.setRollbackOnly();
            return null;
        }));
        assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();
        Future<Receipt> second = Parallel.start(() -> tx.execute(status -> receipts.insert(reserve(id))));
        assertThatThrownBy(() -> second.get(1500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
        release.countDown();
        first.get(10, TimeUnit.SECONDS);
        // the first inserter rolled back, so the second one succeeds
        assertThat(second.get(10, TimeUnit.SECONDS).requestId()).isEqualTo(id);
    }

    @Test
    void repo018_aDuplicateOfACommittedReceiptFailsAfterTheWait() throws Exception {
        TransactionTemplate tx = ctx.bean(TransactionTemplate.class);
        RequestId id = new RequestId(UUID.randomUUID());
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<Receipt> first = Parallel.start(() -> tx.execute(status -> {
            Receipt stored = receipts.insert(reserve(id));
            inserted.countDown();
            awaitQuietly(release);
            return stored;
        }));
        assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();
        Future<Receipt> second = Parallel.start(() -> tx.execute(status -> receipts.insert(reserve(id))));
        assertThatThrownBy(() -> second.get(1500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
        release.countDown();
        assertThat(first.get(10, TimeUnit.SECONDS).requestId()).isEqualTo(id);
        assertThatThrownBy(() -> second.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(DuplicateKeyException.class);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void repo041_originRoundTripsAndTheResponseIsFilledInOnce() {
        RequestId nativeId = new RequestId(UUID.randomUUID());
        RequestId legacyId = new RequestId(UUID.randomUUID());
        ctx.tx(() -> receipts.insert(reserve(nativeId)));
        Receipt legacy = new Receipt(legacyId, ReceiptOperation.TRANSITION, Sha256.of("b".repeat(64)),
                ActorId.ofStored("agent:claude:1"), "ledger_author", JsonDocument.of(json(new Response("COMMITTED"))),
                ReceiptOrigin.LEGACY, LedgerTestContext.START);
        ctx.tx(() -> receipts.insert(legacy));
        assertThat(receipts.findById(nativeId).orElseThrow().origin()).isEqualTo(ReceiptOrigin.NATIVE);
        assertThat(receipts.findById(legacyId)).hasValue(legacy);

        ReceiptCompletionRepository completion = ctx.bean(ReceiptCompletionRepository.class);
        JsonDocument response = JsonDocument.of(json(new Response("COMMITTED")));
        assertThat(ctx.tx(() -> completion.finish(nativeId, response))).isTrue();
        assertThat(ctx.tx(() -> completion.finish(nativeId, response))).as("only once").isFalse();
        assertThat(receipts.findById(nativeId).orElseThrow().response()).isEqualTo(response);
        assertThat(ctx.tx(() -> completion.finish(legacyId, response))).as("a stored response is never replaced")
                .isFalse();
    }

    private record Response(String status) {
    }

    private static String json(Object value) {
        return JSON.writeValueAsString(value);
    }

    /** A document body: text that is hard on the storage layer. */
    private record Body(String name, String text, List<String> pair) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Body> bodies() {
        return Stream.of(
                new Body("korean and emoji", "한국어 텍스트 😀 and \"quotes\"", List.of("a", "b")),
                new Body("newline and backslash", "line 1\nline 2 c:\\dir\\file", List.of("x", "y")),
                new Body("null element", "plain", Arrays.asList(null, "x")),
                new Body("instant with Z", "2026-11-05T10:00:00Z", Arrays.asList("2026-11-05T10:00:00Z", null)),
                new Body("200 KB", "x".repeat(200_000), List.of("big")));
    }

    private HistoryEntry entry(long id, Defect defect, JsonDocument changes, JsonDocument before) {
        return new HistoryEntry(id, LedgerTestContext.START, Seed.ALICE, Channel.AUTHOR.login(),
                ActorAssurance.DECLARED, HistoryEntity.DEFECT, defect.id().value(), HistoryOp.EDIT, null, null,
                changes, before, "a reason of enough length", null, null, null, new RequestId(UUID.randomUUID()),
                null);
    }

    @ParameterizedTest
    @MethodSource("bodies")
    void repo019_jsonTextRoundTripsExactly(Body body) {
        Seed seed = new Seed(ctx);
        Defect defect = seed.defect(seed.project(), 1, "json host", LedgerTestContext.START);
        long id = ctx.tx(() -> ctx.bean(IdAllocator.class).next(Counter.HISTORY));
        JsonDocument document = JsonDocument.of(json(body));
        ctx.tx(() -> ctx.bean(HistoryRepository.class).insert(entry(id, defect, document, document)));
        HistoryEntry read = ctx.bean(HistoryRepository.class).findById(id).orElseThrow();
        assertThat(read.changes()).isEqualTo(document);
        assertThat(read.beforeData()).isEqualTo(document);
        assertThat(JSON.readValue(read.beforeData().text(), Body.class)).isEqualTo(body);
    }

    @Test
    void repo019_aMissingBeforeDataStaysSqlNull() {
        Seed seed = new Seed(ctx);
        Defect defect = seed.defect(seed.project(), 1, "null host", LedgerTestContext.START);
        long id = ctx.tx(() -> ctx.bean(IdAllocator.class).next(Counter.HISTORY));
        ctx.tx(() -> ctx.bean(HistoryRepository.class).insert(entry(id, defect, JsonDocument.EMPTY_OBJECT, null)));
        assertThat(ctx.bean(HistoryRepository.class).findById(id).orElseThrow().beforeData()).isNull();
        assertThat(JdbcClient.create(ctx.pool()).sql("SELECT COUNT(*) FROM history WHERE id = :id AND before_data "
                + "IS NULL").param("id", id).query(Integer.class).single()).isEqualTo(1);
    }
}
