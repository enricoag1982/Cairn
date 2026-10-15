package io.github.enricoag1982.cairn.core.commit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.enricoag1982.cairn.schema.Basis;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Design D4 of {@code add-durable-commit-log}: a sequence resumes after the last recovered commit. */
class CommitSequenceResumeTest {

    private static final Instant LAST = Instant.parse("2026-03-16T10:15:30.123456Z");

    @Test
    void aResumedSequenceContinuesAfterTheRecoveredBasisAndTransactionTime() {
        CommitSequence sequence = new CommitSequence(InstantSource.fixed(Instant.parse("2026-01-01T00:00:00Z")));

        sequence.underWriterLock(() -> {
            sequence.resume(new Basis(7), Optional.of(LAST));
            return null;
        });

        assertThat(sequence.latestBasis()).isEqualTo(new Basis(7));
        CommitSequence.Commit next = sequence.underWriterLock(sequence::next);
        assertThat(next.basis()).isEqualTo(new Basis(8));
        assertThat(next.transactionTime()).as("later than the last one, though the clock went back")
                .isEqualTo(LAST.plusNanos(1_000));
        sequence.underWriterLock(() -> {
            sequence.publish(next);
            return null;
        });
        assertThat(sequence.latestBasis()).isEqualTo(new Basis(8));
    }

    @Test
    void aResumedSequenceUsesTheClockWhenItIsPastTheLastTime() {
        Instant later = LAST.plusSeconds(60);
        CommitSequence sequence = new CommitSequence(InstantSource.fixed(later));

        sequence.underWriterLock(() -> {
            sequence.resume(new Basis(2), Optional.of(LAST));
            return null;
        });

        assertThat(sequence.underWriterLock(sequence::next).transactionTime()).isEqualTo(later);
    }

    @Test
    void anEmptyResumeLeavesTheSequenceAsItWas() {
        CommitSequence sequence = new CommitSequence(InstantSource.fixed(LAST));

        sequence.underWriterLock(() -> {
            sequence.resume(Basis.NONE, Optional.empty());
            return null;
        });

        CommitSequence.Commit next = sequence.underWriterLock(sequence::next);
        assertThat(next.basis()).isEqualTo(new Basis(1));
        assertThat(next.transactionTime()).isEqualTo(LAST);
    }

    @Test
    void resumingNeedsTheWriterLockAndAFreshSequence() {
        CommitSequence sequence = new CommitSequence(InstantSource.fixed(LAST));

        assertThatThrownBy(() -> sequence.resume(new Basis(1), Optional.of(LAST)))
                .isInstanceOf(IllegalStateException.class);
        sequence.underWriterLock(() -> {
            sequence.resume(new Basis(1), Optional.of(LAST));
            assertThatThrownBy(() -> sequence.resume(new Basis(2), Optional.of(LAST)))
                    .isInstanceOf(IllegalStateException.class);
            return null;
        });
        CommitSequence published = new CommitSequence(InstantSource.fixed(LAST));
        published.underWriterLock(() -> {
            published.publish(published.next());
            assertThatThrownBy(() -> published.resume(new Basis(5), Optional.of(LAST)))
                    .isInstanceOf(IllegalStateException.class);
            return null;
        });
    }

    @Test
    void aNegativeBasisIsRefused() {
        CommitSequence sequence = new CommitSequence(InstantSource.fixed(LAST));

        sequence.underWriterLock(() -> {
            assertThatThrownBy(() -> sequence.resume(new Basis(-1), Optional.empty()))
                    .isInstanceOf(IllegalArgumentException.class);
            return null;
        });
    }
}
