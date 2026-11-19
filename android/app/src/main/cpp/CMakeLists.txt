cmake_minimum_required(VERSION 3.22.1)
project(a8mpc C)

set(MPC_DIR ${CMAKE_CURRENT_SOURCE_DIR}/musepack)

add_library(a8mpc SHARED
    a8_mpc_codec.c
    a8_mpc_jni.c
    ${MPC_DIR}/common/crc32.c
    ${MPC_DIR}/common/fastmath.c
    ${MPC_DIR}/libmpcdec/huffman.c
    ${MPC_DIR}/libmpcdec/mpc_bits_reader.c
    ${MPC_DIR}/libmpcdec/mpc_decoder.c
    ${MPC_DIR}/libmpcdec/mpc_demux.c
    ${MPC_DIR}/libmpcdec/mpc_reader.c
    ${MPC_DIR}/libmpcdec/requant.c
    ${MPC_DIR}/libmpcdec/streaminfo.c
    ${MPC_DIR}/libmpcdec/synth_filter.c
    ${MPC_DIR}/libmpcenc/analy_filter.c
    ${MPC_DIR}/libmpcenc/bitstream.c
    ${MPC_DIR}/libmpcenc/encode_sv7.c
    ${MPC_DIR}/libmpcenc/huffsv7.c
    ${MPC_DIR}/libmpcenc/quant.c
    ${MPC_DIR}/libmpcpsy/ans.c
    ${MPC_DIR}/libmpcpsy/cvd.c
    ${MPC_DIR}/libmpcpsy/fft4g.c
    ${MPC_DIR}/libmpcpsy/fft_routines.c
    ${MPC_DIR}/libmpcpsy/profile.c
    ${MPC_DIR}/libmpcpsy/psy.c
    ${MPC_DIR}/libmpcpsy/psy_tab.c
)

target_include_directories(a8mpc PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${MPC_DIR}/include
    ${MPC_DIR}/libmpcdec
    ${MPC_DIR}/libmpcenc
    ${MPC_DIR}/libmpcpsy
)

target_compile_definitions(a8mpc PRIVATE
    CVD_FASTLOG
)

target_compile_options(a8mpc PRIVATE
    -O3
    -Wno-unused-variable
    -Wno-unused-parameter
    -Wno-sign-compare
    -Wno-implicit-function-declaration
    -Wno-incompatible-pointer-types
)

target_link_libraries(a8mpc m log)
