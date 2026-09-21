package io.jfr2grafana.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AgentOptionsTest {

    @Test
    void nullArgsYieldDefaults() {
        AgentOptions options = AgentOptions.parse(null);

        assertThat(options.port()).isEqualTo(AgentOptions.DEFAULT_PORT);
        assertThat(options.host()).isEqualTo(AgentOptions.DEFAULT_HOST);
        assertThat(options.config()).isEmpty();
        assertThat(options.families()).isEmpty();
        assertThat(options.debug()).isFalse();
    }

    @Test
    void blankArgsYieldDefaults() {
        AgentOptions options = AgentOptions.parse("   ");
        assertThat(options).isEqualTo(AgentOptions.defaults());
    }

    @Test
    void parsesPort() {
        assertThat(AgentOptions.parse("port=9500").port()).isEqualTo(9500);
    }

    @Test
    void parsesHost() {
        assertThat(AgentOptions.parse("host=127.0.0.1").host()).isEqualTo("127.0.0.1");
    }

    @Test
    void blankHostFallsBackToDefault() {
        assertThat(AgentOptions.parse("host=").host()).isEqualTo(AgentOptions.DEFAULT_HOST);
    }

    @Test
    void parsesConfigPath() {
        AgentOptions options = AgentOptions.parse("config=/etc/jfr2grafana/extra.yaml");
        assertThat(options.config()).contains(Path.of("/etc/jfr2grafana/extra.yaml"));
    }

    @Test
    void parsesFamiliesWithPlusSeparator() {
        AgentOptions options = AgentOptions.parse("families=memory-gc+cpu-threads");
        assertThat(options.families()).containsExactly("memory-gc", "cpu-threads");
    }

    @Test
    void parsesFamiliesWithCommaSeparatorEvenThoughCommaIsTheTopLevelSeparator() {
        AgentOptions options = AgentOptions.parse("families=memory-gc,cpu-threads,port=9500");
        assertThat(options.families()).containsExactly("memory-gc", "cpu-threads");
        assertThat(options.port()).isEqualTo(9500);
    }

    @Test
    void parsesDebugTrueAndFalse() {
        assertThat(AgentOptions.parse("debug=true").debug()).isTrue();
        assertThat(AgentOptions.parse("debug=false").debug()).isFalse();
        assertThat(AgentOptions.parse("debug=TRUE").debug()).isTrue();
    }

    @Test
    void parsesAllOptionsTogether() {
        AgentOptions options = AgentOptions.parse(
                "port=9999,host=10.0.0.5,config=/tmp/extra.yaml,families=memory-gc+jit-io-class,debug=true");

        assertThat(options.port()).isEqualTo(9999);
        assertThat(options.host()).isEqualTo("10.0.0.5");
        assertThat(options.config()).contains(Path.of("/tmp/extra.yaml"));
        assertThat(options.families()).containsExactly("memory-gc", "jit-io-class");
        assertThat(options.debug()).isTrue();
    }

    @Test
    void malformedPortThrows() {
        assertThatThrownBy(() -> AgentOptions.parse("port=abc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("port");
    }

    @Test
    void outOfRangePortThrows() {
        assertThatThrownBy(() -> AgentOptions.parse("port=99999"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("port");
    }

    @Test
    void malformedDebugThrows() {
        assertThatThrownBy(() -> AgentOptions.parse("debug=yes"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("debug");
    }

    @Test
    void bareTokenWithoutALeadingKeyThrows() {
        assertThatThrownBy(() -> AgentOptions.parse("justAValue"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
