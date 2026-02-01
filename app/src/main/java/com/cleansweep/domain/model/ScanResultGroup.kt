/*
 * CleanSweep
 * Copyright (c) 2025 LoopOtto
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.cleansweep.domain.model

import com.cleansweep.data.model.MediaItem
import java.security.MessageDigest

/**
 * A sealed interface representing a group of scan results.
 * This can be either a group of exact duplicates or a group of visually similar media.
 */
sealed interface ScanResultGroup {
    val items: List<MediaItem>
    val uniqueId: String

    /**
     * A stable identifier based strictly on the current set of items in the group.
     * This ID is used for "Hide Group" functionality, ensuring that if a new
     * duplicate is added to the folder, the group composition changes and it reappears.
     */
    val compositionId: String
        get() {
            val sortedPaths = items.map { it.id }.sorted().joinToString("|")
            return MessageDigest.getInstance("MD5")
                .digest(sortedPaths.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }

    /**
     * Creates a new instance of the group with an updated list of items.
     * This is used after validation to filter out items that no longer exist.
     */
    fun withUpdatedItems(newItems: List<MediaItem>): ScanResultGroup
}

/**
 * Represents a group of files that are byte-for-byte identical.
 *
 * @property signature The SHA-256 hash or other signature common to all files in this group.
 * @property items The list of identical media items. These are pre-sorted by date.
 * @property sizePerFile The size of each individual file in the group.
 */
data class DuplicateGroup(
    val signature: String,
    override val items: List<MediaItem>,
    val sizePerFile: Long
) : ScanResultGroup {
    override val uniqueId: String get() = signature

    override fun withUpdatedItems(newItems: List<MediaItem>): ScanResultGroup = copy(items = newItems)
}

/**
 * Represents a group of visually similar media items (images or videos).
 *
 * @property pHash The perceptual hash common to all media items in this group.
 * @property items The list of visually similar media items.
 */
data class SimilarGroup(
    val pHash: String,
    override val items: List<MediaItem>
) : ScanResultGroup {
    override val uniqueId: String get() = pHash
    override fun withUpdatedItems(newItems: List<MediaItem>): ScanResultGroup = copy(items = newItems)
}
