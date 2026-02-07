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

package com.cleansweep.util

object HiddenFileFilter {

    private const val hiddenPrefix = "-"
    private val scanExclusionPrefixes = listOf(".", "_")

    /**
     * Checks if a given file path or name should be considered a "hidden" or system file
     * based on common Android/Linux conventions. Used for UI filtering.
     *
     * @param fileName The name of the file (e.g., ".nomedia", "_thumb.jpg", "-app_img.jpg).
     * @return `true` if the file is considered hidden, `false` otherwise.
     */
    fun toBeHidden(fileName: String): Boolean {
        return fileName.startsWith(hiddenPrefix)
    }

    /**
     * Checks if a full file path should be excluded from a deep scan. This is more aggressive
     * than `isNormallyHidden` as it checks every directory component in the path, not just
     * the final filename. It specifically looks for `.` and `_` prefixes, which almost
     * always denote cache, thumbnail, hidden, or temporary directories/files that are irrelevant for scanning.
     *
     * @param path The full file path (e.g., "/storage/emulated/0/DCIM/.thumbnails/1234.jpg").
     * @return `true` if any part of the path is considered hidden for scanning, `false` otherwise.
     */
    fun isPathExcludedFromScan(path: String): Boolean {
        // Splitting by '/' and filtering out empty strings handles potential leading slashes.
        return path.split('/').any { component ->
            scanExclusionPrefixes.any { prefix -> component.startsWith(prefix) }
        }
    }

    /**
     * A comprehensive check to determine if a file should be hidden from the user in UI lists.
     * Combines both the scan exclusion logic (for paths like /.thumbnails/) and the
     * specific filename logic (for files like -12345.jpg).
     *
     * @param path The full file path to check.
     * @return `true` if the file should be considered hidden in the UI, `false` otherwise.
     */
    fun isUiHidden(path: String): Boolean {
        return isPathExcludedFromScan(path) || toBeHidden(path.substringAfterLast('/'))
    }

    /**
     * Filters a list of file paths, returning only those that are not considered
     * "normally hidden" system files based on their filename. Used for UI filtering.
     *
     * @param paths A list of full file paths.
     * @return A new list containing only user-facing file paths.
     */
    fun filterHiddenFiles(paths: List<String>): List<String> {
        return paths.filterNot { path ->
            val fileName = path.substringAfterLast('/')
            toBeHidden(fileName)
        }
    }
}
