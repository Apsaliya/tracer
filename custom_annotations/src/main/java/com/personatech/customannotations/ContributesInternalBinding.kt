package com.personatech.customannotations

import kotlin.annotation.AnnotationRetention.RUNTIME
import kotlin.annotation.AnnotationTarget.CLASS
import kotlin.reflect.KClass

@Target(CLASS)
@Retention(RUNTIME)
annotation class ContributesInternalBinding(
  val scope: KClass<*>,
  val boundTypes: Array<KClass<*>> = [],
  val excludeQualifier: Boolean = false
)