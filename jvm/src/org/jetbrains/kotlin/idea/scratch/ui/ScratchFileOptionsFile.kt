/*
 * Copyright 2010-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.scratch.ui

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.idea.core.util.AbstractFileAttributePropertyService
import org.jetbrains.kotlin.idea.scratch.ScratchFileOptions
import org.jetbrains.kotlin.idea.util.application.getServiceSafe

@Service
internal class ScratchFileOptionsFile: AbstractFileAttributePropertyService<ScratchFileOptions>(
    name = "kotlin-scratch-file-options",
    version = 1,
    read = { ScratchFileOptions(readBoolean(), readBoolean(), readBoolean()) },
    write = {
        writeBoolean(it.isRepl)
        writeBoolean(it.isMakeBeforeRun)
        writeBoolean(it.isInteractiveMode)
    }
) {
    companion object {
        operator fun get(project: Project, file: VirtualFile) = project.getServiceSafe<ScratchFileOptionsFile>()[file]

        operator fun set(project: Project, file: VirtualFile, newValue: ScratchFileOptions?) {
            project.getServiceSafe<ScratchFileOptionsFile>()[file] = newValue
        }
    }
}