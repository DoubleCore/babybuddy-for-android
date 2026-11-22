cmake_minimum_required(VERSION 3.18.1)
project(babybuddy_ncnn_pose LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(Threads REQUIRED)

add_library(ncnn SHARED IMPORTED)
set_target_properties(ncnn PROPERTIES
    IMPORTED_LOCATION "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libncnn.so"
)

add_library(babybuddy_ncnn_pose SHARED
    yolo_pose_ncnn_jni.cpp
)

target_include_directories(babybuddy_ncnn_pose PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/ncnn-headers/${ANDROID_ABI}/ncnn"
    "${CMAKE_CURRENT_SOURCE_DIR}/ncnn-headers/${ANDROID_ABI}"
)

target_compile_options(babybuddy_ncnn_pose PRIVATE
    -O3
    -ffast-math
    -fno-rtti
    -fno-exceptions
    -fopenmp
    -fvisibility=hidden
)

target_link_options(babybuddy_ncnn_pose PRIVATE
    -fopenmp
    -static-openmp
    -Wl,-z,max-page-size=16384
)

target_link_libraries(babybuddy_ncnn_pose
    ncnn
    Threads::Threads
    vulkan
    jnigraphics
    android
    log
)
