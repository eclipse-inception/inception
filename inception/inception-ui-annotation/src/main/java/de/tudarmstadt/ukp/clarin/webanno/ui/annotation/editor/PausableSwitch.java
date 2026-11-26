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

import java.io.Serializable;
import java.util.Optional;

import org.danekja.java.util.function.serializable.SerializableSupplier;

/**
 * Whether the user wants a mode that works across two document editors, such as scrolling them
 * together.
 * <p>
 * Starts off. Kept when an editor changes document - the mode then only pauses while something
 * stands in its way. An owner that saves the mode restores it with {@link #enable()}.
 *
 * @param <O>
 *            what can stand in the way of the mode.
 */
public class PausableSwitch<O>
    implements Serializable
{
    private static final long serialVersionUID = -6630343527415904772L;

    private final SerializableSupplier<Optional<O>> obstacle;

    private boolean enabled = false;

    /**
     * @param aObstacle
     *            what stands in the way of the mode right now, or {@link Optional#empty()} if
     *            nothing does.
     */
    public PausableSwitch(SerializableSupplier<Optional<O>> aObstacle)
    {
        obstacle = aObstacle;
    }

    public boolean isEnabled()
    {
        return enabled;
    }

    /**
     * @return what stands in the way of the mode right now, or {@link Optional#empty()} if nothing
     *         does.
     */
    public Optional<O> getObstacle()
    {
        return obstacle.get();
    }

    /**
     * @return whether the user can switch the mode now: off at any time, but on only while nothing
     *         stands in its way. While it is on but blocked, it is paused, and it resumes by itself
     *         once nothing stands in its way any more.
     */
    public boolean isToggleable()
    {
        return enabled || getObstacle().isEmpty();
    }

    /**
     * Switch the mode if it is {@link #isToggleable() toggleable}.
     */
    public void toggle()
    {
        if (!isToggleable()) {
            return;
        }

        enabled = !enabled;
    }

    /**
     * Switch the mode on, whether or not anything currently stands in its way. While something
     * does, it is paused.
     */
    public void enable()
    {
        enabled = true;
    }
}
