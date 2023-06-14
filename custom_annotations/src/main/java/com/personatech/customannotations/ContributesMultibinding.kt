package com.personatech.customannotations

import com.personatech.customannotations.MultibindingType.Map
import com.squareup.moshi.JsonClass
import kotlin.annotation.AnnotationRetention.RUNTIME
import kotlin.annotation.AnnotationTarget.CLASS
import kotlin.reflect.KClass

@Target(CLASS)
@Retention(RUNTIME)
annotation class ContributesMultibinding(
  val bindingType: MultibindingType = Map,
  val boundTypes: Array<KClass<*>> = [],
  val contributorType: ContributorType
)

@JsonClass(generateAdapter = false)
enum class MultibindingType {
  Map,
  Set
}

@JsonClass(generateAdapter = false)
enum class ContributorType {
  Binder,
  Object
}
