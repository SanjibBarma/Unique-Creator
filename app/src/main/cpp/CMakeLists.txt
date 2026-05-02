cmake_minimum_required(VERSION 3.22.1)

project("uniquecreator")

add_library(
        unique-security
        SHARED
        native-lib.cpp
)

find_library(
        log-lib
        log
)

target_link_libraries(
        unique-security
        ${log-lib}
)