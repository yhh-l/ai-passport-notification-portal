idf_component_register(SRCS "hooks.c" "../../components/play_store/play_store.c"
    INCLUDE_DIRS "../../components/play_store/include" REQUIRES bootloader_support spi_flash)
