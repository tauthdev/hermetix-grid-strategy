package com.tripleauth.grid

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class GridApplication

fun main(args: Array<String>) {
    runApplication<GridApplication>(*args)
}
