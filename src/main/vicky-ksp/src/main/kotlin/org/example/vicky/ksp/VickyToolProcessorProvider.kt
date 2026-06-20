package org.example.vicky.ksp

import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment

class VickyToolProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return VickyToolProcessor(
            codeGenerator = environment.codeGenerator,
            logger = environment.logger
        )
    }
}
