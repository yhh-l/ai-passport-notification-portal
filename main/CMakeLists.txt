idf_component_register(
    SRCS "main.c"
         "app_ble.c"
         "app_firmware.c"
         "fonts/font_chinese_16.c"
    INCLUDE_DIRS "."
    REQUIRES app_update bsp bt nvs_flash
)
