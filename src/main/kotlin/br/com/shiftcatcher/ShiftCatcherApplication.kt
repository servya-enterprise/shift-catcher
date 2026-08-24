package br.com.shiftcatcher

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
class ShiftCatcherApplication

fun main(args: Array<String>) {
    runApplication<ShiftCatcherApplication>(*args)
}
