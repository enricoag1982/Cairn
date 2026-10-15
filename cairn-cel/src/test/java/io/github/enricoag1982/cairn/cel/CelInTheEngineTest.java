package io.github.enricoag1982.cairn.cel;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.enricoag1982.cairn.core.SchemaRegistries;
import io.github.enricoag1982.cairn.core.StoreSettings;
import io.github.enricoag1982.cairn.core.Stores;
import io.github.enricoag1982.cairn.data.CommitResult;
import io.github.enricoag1982.cairn.data.Store;
import io.github.enricoag1982.cairn.data.Transaction;
import io.github.enricoag1982.cairn.expressions.ExpressionLanguage;
import io.github.enricoag1982.cairn.fixtures.Definitions;
import io.github.enricoag1982.cairn.fixtures.ReferenceModel;
import io.github.enricoag1982.cairn.fixtures.ReferenceStories;
import io.github.enricoag1982.cairn.schema.Problem;
import io.github.enricoag1982.cairn.schema.ProblemCode;
import io.github.enricoag1982.cairn.schema.RegistrationResult;
import java.time.InstantSource;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The language in the engine: it is found without being named, and the engine compiles and runs its checks. */
class CelInTheEngineTest {

    @Test
    @DisplayName("The engine finds the language on the class path")
    void theEngineFindsTheLanguageOnTheClassPath() {
        Optional<ExpressionLanguage> language = StoreSettings.builder().build().expressionLanguage();

        assertThat(language).containsInstanceOf(CelLanguage.class);
        assertThat(StoreSettings.builder().noExpressionLanguage().build().expressionLanguage()).isEmpty();
    }

    @Test
    @DisplayName("A store registers a check and rejects a version that breaks it")
    void aStoreRegistersACheckAndRejectsAVersionThatBreaksIt() {
        Store store = Stores.inMemory(StoreSettings.builder().rules(ReferenceModel.executableRules())
                .declaredRules(ReferenceModel.declaredRules()).build());
        RegistrationResult registered = store.schema().register(ReferenceModel.origin("1.0.0"),
                Definitions.withCheck(ReferenceModel.definition(), "Account", "balance-not-negative",
                        "self.balance >= 0.0", "A balance cannot be negative."));
        assertThat(registered).isInstanceOf(RegistrationResult.Applied.class);

        CommitResult result = store.commit(Transaction.by("importer")
                .create(ReferenceStories.A1, "Account", ReferenceStories.from(1, 1),
                        ReferenceStories.values("number", "A-1", "currency", "EUR", "balance", -1))
                .build());

        assertThat(result).isInstanceOf(CommitResult.Rejected.class);
        Problem problem = ((CommitResult.Rejected) result).problems().stream()
                .filter(candidate -> candidate.code() == ProblemCode.CHECK_VIOLATED).findFirst().orElseThrow();
        assertThat(problem.message()).contains("balance-not-negative").contains("A balance cannot be negative.");
    }

    @Test
    @DisplayName("A schema registry rejects an expression that does not compile, with the position")
    void aSchemaRegistryRejectsAnExpressionThatDoesNotCompile() {
        var registry = SchemaRegistries.inMemory(InstantSource.system(), ReferenceModel.rules());

        var problems = registry.validate(ReferenceModel.origin("1.0.0"), Definitions.withCheck(
                ReferenceModel.definition(), "Account", "balance-not-negative", "self.balance >=", null));

        assertThat(problems).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo(ProblemCode.INVALID_CHECK);
            assertThat(problem.message()).contains("at line 1, column 16");
        });
    }
}
