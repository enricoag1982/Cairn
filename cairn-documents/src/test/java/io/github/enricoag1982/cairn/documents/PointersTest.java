package io.github.enricoag1982.cairn.documents;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PointersTest {

    @Test
    void membersAndItemsAreAppended() {
        assertThat(Pointers.child("", "entityTypes")).isEqualTo("/entityTypes");
        assertThat(Pointers.child("/entityTypes", "Organization")).isEqualTo("/entityTypes/Organization");
        assertThat(Pointers.child("/entityTypes/Organization/rules", 0)).isEqualTo("/entityTypes/Organization/rules/0");
    }

    @Test
    void slashesAndTildesAreEscaped() {
        assertThat(Pointers.child("/entityTypes", "a/b")).isEqualTo("/entityTypes/a~1b");
        assertThat(Pointers.child("", "a~b")).isEqualTo("/a~0b");
        assertThat(Pointers.escape("~/")).isEqualTo("~0~1");
        assertThat(Pointers.child("", "")).isEqualTo("/");
    }
}
