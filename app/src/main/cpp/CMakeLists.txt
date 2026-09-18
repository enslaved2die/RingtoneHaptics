cmake_minimum_required(VERSION 3.22.1)

project("ringtonehaptics_native")

include_directories(
    vendor/ogg/include
    vendor/vorbis/include
    vendor/vorbis/lib
)

add_library(
    ringtonehaptics_native
    SHARED
    ogg_vorbis_encoder.cpp
    vendor/ogg/src/bitwise.c
    vendor/ogg/src/framing.c
    vendor/vorbis/lib/analysis.c
    vendor/vorbis/lib/bitrate.c
    vendor/vorbis/lib/block.c
    vendor/vorbis/lib/codebook.c
    vendor/vorbis/lib/envelope.c
    vendor/vorbis/lib/floor0.c
    vendor/vorbis/lib/floor1.c
    vendor/vorbis/lib/info.c
    vendor/vorbis/lib/lookup.c
    vendor/vorbis/lib/lpc.c
    vendor/vorbis/lib/lsp.c
    vendor/vorbis/lib/mapping0.c
    vendor/vorbis/lib/mdct.c
    vendor/vorbis/lib/psy.c
    vendor/vorbis/lib/registry.c
    vendor/vorbis/lib/res0.c
    vendor/vorbis/lib/sharedbook.c
    vendor/vorbis/lib/smallft.c
    vendor/vorbis/lib/synthesis.c
    vendor/vorbis/lib/vorbisenc.c
    vendor/vorbis/lib/window.c
)

find_library(log-lib log)
find_library(m-lib m)

target_link_libraries(
    ringtonehaptics_native
    ${log-lib}
    ${m-lib}
)

# 16KB page size compatibility (required for newer Pixel/Android hardware) -
# https://developer.android.com/guide/practices/page-sizes
target_link_options(
    ringtonehaptics_native
    PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)
