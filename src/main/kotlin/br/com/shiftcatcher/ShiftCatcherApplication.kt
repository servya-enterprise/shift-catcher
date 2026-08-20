package br.com.shiftcatcher

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class ShiftCatcherApplication

fun main(args: Array<String>) {
    runApplication<ShiftCatcherApplication>(*args)
}
