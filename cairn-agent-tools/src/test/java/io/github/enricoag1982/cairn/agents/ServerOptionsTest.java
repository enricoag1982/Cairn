package io.github.enricoag1982.cairn.agents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.enricoag1982.cairn.agents.AgentPolicy.Decision;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The options of the server (design D7 of {@code add-agent-tools}). */
class ServerOptionsTest {

    @Test
    void thePrincipalIsTheOnlyRequiredOption() throws Exception {
        ServerOptions options = ServerOptions.parse(new String[] {"--principal", "agent:demo"});

        assertThat(options.principal()).isEqualTo("agent:demo");
        assertThat(options.schemas()).isEmpty();
        assertThat(options.onBehalfOf()).isEmpty();
        assertThat(options.policy()).isEqualTo(AgentPolicy.defaults());
        assertThat(options.help()).isFalse();
    }

    @Test
    void everyOptionCanBeGiven() throws Exception {
        ServerOptions options = ServerOptions.parse(new String[] {"--schema", "a.yaml", "--principal", "agent:demo",
                "--schema", "b.yaml", "--on-behalf-of", "alice", "--policy", "schema=approve,data=deny"});

        assertThat(options.schemas()).containsExactly(Path.of("a.yaml"), Path.of("b.yaml"));
        assertThat(options.onBehalfOf()).isEqualTo(Optional.of("alice"));
        assertThat(options.policy()).isEqualTo(new AgentPolicy(Decision.REQUIRE_APPROVAL, Decision.DENY));
    }

    @Test
    void anOptionMayBeGivenWithAnEqualsSign() throws Exception {
        ServerOptions options = ServerOptions.parse(new String[] {"--principal=agent:demo", "--schema=a.yaml",
                "--policy=data=approve"});

        assertThat(options.principal()).isEqualTo("agent:demo");
        assertThat(options.schemas()).containsExactly(Path.of("a.yaml"));
        assertThat(options.policy()).isEqualTo(new AgentPolicy(Decision.ALLOW, Decision.REQUIRE_APPROVAL));
    }

    @Test
    void aPolicyThatNamesOneKindLeavesTheOtherAllowed() throws Exception {
        assertThat(ServerOptions.parse(new String[] {"--principal", "p", "--policy", "schema=deny"}).policy())
                .isEqualTo(new AgentPolicy(Decision.DENY, Decision.ALLOW));
        assertThat(ServerOptions.parse(new String[] {"--principal", "p", "--policy", "data=allow, schema=approve"})
                .policy()).isEqualTo(new AgentPolicy(Decision.REQUIRE_APPROVAL, Decision.ALLOW));
    }

    @Test
    void helpNeedsNoPrincipal() throws Exception {
        assertThat(ServerOptions.parse(new String[] {"--help"}).help()).isTrue();
        assertThat(ServerOptions.parse(new String[] {"-h"}).help()).isTrue();
    }

    @Test
    void wrongOptionsAreRejectedWithAMessage() {
        assertInvalid(new String[] {}, "--principal is required");
        assertInvalid(new String[] {"--principal", " "}, "--principal is required");
        assertInvalid(new String[] {"--principal"}, "--principal needs a value");
        assertInvalid(new String[] {"--principal", "p", "--schema"}, "--schema needs a value");
        assertInvalid(new String[] {"--principal", "p", "--on-behalf-of", ""}, "must not be blank");
        assertInvalid(new String[] {"--principal", "p", "stray"}, "Unknown option stray");
        assertInvalid(new String[] {"--principal", "p", "--policy", "schema=maybe"}, "must be allow, approve or deny");
        assertInvalid(new String[] {"--principal", "p", "--policy", "schemas=allow"}, "only schema and data");
        assertInvalid(new String[] {"--principal", "p", "--policy", "allow"}, "must be like schema=allow");
        assertInvalid(new String[] {"--principal", "p", "--policy", "schema=allow,schema=deny"}, "schema twice");
        assertInvalid(new String[] {"--principal", "p", "--policy", "data=allow,data=deny"}, "data twice");
        assertInvalid(new String[] {"--principal", "p", "--policy", "schema=allow", "--policy", "data=deny"},
                "given twice");
    }

    private static void assertInvalid(String[] args, String message) {
        assertThatThrownBy(() -> ServerOptions.parse(args)).isInstanceOf(ServerOptions.Invalid.class)
                .hasMessageContaining(message);
    }
}
