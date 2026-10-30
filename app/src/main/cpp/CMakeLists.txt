# 1.9.0 on-device LLM runtime: llama.cpp (pinned submodule third_party/llama.cpp @ 8642541, MIT) CPU-only.
cmake_minimum_required(VERSION 3.22.1)
project(broker_llm C CXX)

# Always optimize the runtime, even in debug APKs (an -O0 llama.cpp is ~10x slower).
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
foreach(lang C CXX)
    set(CMAKE_${lang}_FLAGS_DEBUG "-O3 -DNDEBUG" CACHE STRING "" FORCE)
    set(CMAKE_${lang}_FLAGS_RELEASE "-O3 -DNDEBUG" CACHE STRING "" FORCE)
endforeach()

set(LLAMA_SRC ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/llama.cpp)

# Lean, deterministic build: no network fetches (KleidiAI), no OpenMP runtime, no tools/tests/server/curl.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_HTML OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE ON CACHE BOOL "" FORCE)
# TC22 QCM5430: Cortex-A55/A78 = ARMv8.2-A with dotprod + fp16 (verified /proc/cpuinfo: asimddp fphp asimdhp; no i8mm/SVE).
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)

add_subdirectory(${LLAMA_SRC} llama-build)

add_library(broker_llm SHARED llm_jni.cpp)
target_include_directories(broker_llm PRIVATE ${LLAMA_SRC}/include)
target_link_libraries(broker_llm PRIVATE llama ggml android log)
target_compile_options(broker_llm PRIVATE -O3 -fvisibility=hidden)
target_link_options(broker_llm PRIVATE -Wl,--gc-sections -Wl,-z,max-page-size=16384)
