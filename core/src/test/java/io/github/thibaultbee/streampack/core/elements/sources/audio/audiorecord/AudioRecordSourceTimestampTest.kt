/*
 * Copyright (C) 2026 Thibault B.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.thibaultbee.streampack.core.elements.sources.audio.audiorecord

import io.github.thibaultbee.streampack.core.elements.sources.audio.audiorecord.AudioRecordSource.Companion.firstFrameTimestampInUs
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioRecordSourceTimestampTest {
    @Test
    fun `timestamp is the first frame of the buffer just read`() {
        // 1024 frames already read, 1024 just read and 441 still buffered: framePosition 2489.
        assertEquals(
            10_000_000L - (1024 + 441) * 1_000_000L / 44_100,
            firstFrameTimestampInUs(10_000_000_000L, 2489, 1024, 2646 + 1024, 44_100)
        )
    }

    @Test
    fun `framePosition behind the frames just read still extrapolates`() {
        assertEquals(
            10_000_000L - 1_000L,
            firstFrameTimestampInUs(10_000_000_000L, 1048, 1000, 2000, 48_000)
        )
    }

    @Test
    fun `framePosition behind the frames read extrapolates forward`() {
        // A stale timestamp while the reader drains a backlog: frame 4410 was captured at nanoTime,
        // so frame 8820, read after it, was captured 100 ms later.
        assertEquals(
            10_000_000L + 100_000L,
            firstFrameTimestampInUs(10_000_000_000L, 4410, 8820, 44_100, 44_100)
        )
    }

    @Test
    fun `framePosition on the first frame gives nanoTime`() {
        assertEquals(10_000_000L, firstFrameTimestampInUs(10_000_000_000L, 0, 0, 2000, 48_000))
    }

    @Test
    fun `framePosition from another start is rejected`() {
        // Not reset by the restart.
        assertEquals(-1L, firstFrameTimestampInUs(10_000_000_000L, 500_000, 1024, 3670, 44_100))
        // Reset, but frames read were not.
        assertEquals(-1L, firstFrameTimestampInUs(10_000_000_000L, 1024, 500_000, 3670, 44_100))
    }

    @Test
    fun `framesAhead bounds are inclusive on both sides`() {
        assertEquals(
            10_000_000L - 3670 * 1_000_000L / 44_100,
            firstFrameTimestampInUs(10_000_000_000L, 3670, 0, 3670, 44_100)
        )
        assertEquals(-1L, firstFrameTimestampInUs(10_000_000_000L, 3671, 0, 3670, 44_100))
        assertEquals(
            10_000_000L + 3670 * 1_000_000L / 44_100,
            firstFrameTimestampInUs(10_000_000_000L, 0, 3670, 3670, 44_100)
        )
        assertEquals(-1L, firstFrameTimestampInUs(10_000_000_000L, 0, 3671, 3670, 44_100))
    }
}
