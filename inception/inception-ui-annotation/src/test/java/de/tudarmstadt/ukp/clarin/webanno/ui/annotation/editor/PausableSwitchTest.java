/*
 * Licensed to the Technische Universität Darmstadt under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The Technische Universität Darmstadt
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.tudarmstadt.ukp.clarin.webanno.ui.annotation.editor;

import static de.tudarmstadt.ukp.clarin.webanno.ui.annotation.editor.ViewportSyncLink.Obstacle.DIFFERENT_DOCUMENTS;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.tudarmstadt.ukp.clarin.webanno.ui.annotation.editor.ViewportSyncLink.Obstacle;

class PausableSwitchTest
{
    private Optional<Obstacle> obstacle;

    private PausableSwitch<Obstacle> sut;

    @BeforeEach
    void setup()
    {
        obstacle = Optional.empty();
        sut = new PausableSwitch<>(() -> obstacle);
    }

    @Test
    void thatTogglingSwitchesOnAndOff()
    {
        assertThat(sut.isEnabled()).as("starts off").isFalse();

        sut.toggle();
        assertThat(sut.isEnabled()).as("switched on").isTrue();

        sut.toggle();
        assertThat(sut.isEnabled()).as("switched off").isFalse();
    }

    @Test
    void thatSwitchCannotBeSwitchedOnWhileBlocked()
    {
        obstacle = Optional.of(DIFFERENT_DOCUMENTS);

        assertThat(sut.isToggleable()).isFalse();

        sut.toggle();
        assertThat(sut.isEnabled()).isFalse();
    }

    @Test
    void thatEnablingSwitchesOnEvenWhileBlocked()
    {
        obstacle = Optional.of(DIFFERENT_DOCUMENTS);

        sut.enable();

        assertThat(sut.isEnabled()).isTrue();
        assertThat(sut.isToggleable()).as("can be switched off while paused").isTrue();
    }

    @Test
    void thatPausedSwitchCanStillBeSwitchedOff()
    {
        sut.toggle();

        // One of two paged panes opens another document
        obstacle = Optional.of(DIFFERENT_DOCUMENTS);

        assertThat(sut.isEnabled()).as("kept while paused").isTrue();
        assertThat(sut.isToggleable()).isTrue();

        sut.toggle();
        assertThat(sut.isEnabled()).isFalse();

        // The panes show the same document again
        obstacle = Optional.empty();

        assertThat(sut.isEnabled()).as("does not resume once switched off").isFalse();
    }
}
