#include "app_firmware.h"
#include "play_store.h"
#include "esp_partition.h"
#include "esp_image_format.h"
#include "nvs.h"
#include "bootloader_utility.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>

static unsigned char flash[0x800000], blob[sizeof(play_catalog_t)], staged[sizeof(play_catalog_t)];
static bool exists, reject_verify, reject_commit, reject_erase, reject_write, legacy;
static unsigned erased, boot_count;
static uint32_t boot_address;
static const esp_partition_t pools[] = {{PLAY_POOL_A,PLAY_POOL_A_SIZE,"play_a"},{PLAY_POOL_B,PLAY_POOL_B_SIZE,"play_b"},{PLAY_BOOT_ADDRESS,4096,"play_boot"}};
void bootloader_after_init(void);
const esp_partition_t *esp_partition_find_first(int t, int s, const char *name) {
    (void)t;(void)s;for(unsigned i=0;i<3;i++) if(!strcmp(name,pools[i].label)) return &pools[i]; return NULL;
}
esp_err_t esp_partition_erase_range(const esp_partition_t *p,size_t o,size_t n) {
    assert(p && o+n<=p->size && o%4096==0 && n%4096==0);
    if(reject_erase) return ESP_FAIL;
    memset(flash+p->address+o,255,n);erased++;return ESP_OK;
}
esp_err_t esp_partition_write(const esp_partition_t *p,size_t o,const void *data,size_t n) {
    assert(p && o+n<=p->size);if(reject_write)return ESP_FAIL;
    memcpy(flash+p->address+o,data,n);return ESP_OK;
}
esp_err_t esp_image_verify(int m,const esp_partition_pos_t *p,esp_image_metadata_t *out) {
    (void)m;(void)out;return !reject_verify && p->offset+p->size<=sizeof(flash) && flash[p->offset]==0xe9 ? ESP_OK : ESP_FAIL;
}
esp_err_t nvs_flash_init_partition(const char *p){assert(!strcmp(p,"play_index"));return ESP_OK;}
esp_err_t nvs_open_from_partition(const char *p,const char *n,int mode,nvs_handle_t *h){
    assert(!strcmp(p,"play_index")&&!strcmp(n,"play_store_v3"));*h=1;return exists||mode==NVS_READWRITE?ESP_OK:ESP_ERR_NVS_NOT_FOUND;
}
esp_err_t nvs_open(const char *n,int mode,nvs_handle_t *h){(void)n;(void)mode;*h=2;return legacy?ESP_OK:ESP_ERR_NVS_NOT_FOUND;}
esp_err_t nvs_get_blob(nvs_handle_t h,const char *k,void *p,size_t *n){(void)h;(void)k;if(!exists)return ESP_ERR_NVS_NOT_FOUND;assert(*n>=sizeof(blob));memcpy(p,blob,sizeof(blob));*n=sizeof(blob);return ESP_OK;}
esp_err_t nvs_set_blob(nvs_handle_t h,const char *k,const void *p,size_t n){(void)h;(void)k;assert(n==sizeof(blob));memcpy(staged,p,n);return ESP_OK;}
esp_err_t nvs_commit(nvs_handle_t h){(void)h;if(reject_commit)return ESP_FAIL;memcpy(blob,staged,sizeof(blob));exists=true;return ESP_OK;}
void nvs_close(nvs_handle_t h){(void)h;}
esp_err_t nvs_get_u8(nvs_handle_t h,const char *k,uint8_t *v){(void)h;if(!strcmp(k,"s0_valid")||!strcmp(k,"s1_valid")||!strcmp(k,"s2_valid")){*v=1;return ESP_OK;}return ESP_ERR_NVS_NOT_FOUND;}
esp_err_t nvs_get_u32(nvs_handle_t h,const char *k,uint32_t *v){(void)h;(void)k;*v=512;return ESP_OK;}
esp_err_t nvs_get_str(nvs_handle_t h,const char *k,char *v,size_t *n){(void)h;(void)k;assert(*n>=7);strcpy(v,"Legacy");return ESP_OK;}
esp_err_t bootloader_flash_read(size_t a,void *d,size_t n,bool decrypt){(void)decrypt;memcpy(d,flash+a,n);return ESP_OK;}
esp_err_t bootloader_flash_erase_sector(size_t sector){if(reject_erase)return ESP_FAIL;memset(flash+sector*4096,255,4096);return ESP_OK;}
void bootloader_utility_load_boot_image(const bootloader_state_t *s,int index){assert(index==FACTORY_INDEX);boot_count++;boot_address=s->factory.offset;}
static void reset(void){memset(flash,255,sizeof(flash));exists=false;reject_verify=reject_commit=reject_erase=reject_write=legacy=false;boot_count=erased=0;app_firmware_init();}
static void install(unsigned id){unsigned char image[512]={0xe9};assert(app_firmware_begin(id,sizeof(image),"Test")==ESP_OK);assert(app_firmware_write(image,sizeof(image))==ESP_OK);assert(app_firmware_finish()==ESP_OK);}
int main(void){
    reset();for(unsigned i=0;i<5;i++)install(i);
    assert(app_firmware_total_free()==0x6c0000-5*65536);
    unsigned before=erased;assert(app_firmware_begin(0,512,"overwrite")!=ESP_OK);assert(erased==before);
    assert(app_firmware_begin(5,512,"partial")==ESP_OK);unsigned char data[512]={0xe9};
    assert(app_firmware_write(data,256)==ESP_OK);app_firmware_cancel(ESP_FAIL);app_firmware_init();
    assert(app_firmware_total_free()==0x6c0000-5*65536);
    reject_verify=true;assert(app_firmware_begin(5,512,"bad")==ESP_OK);app_firmware_write(data,512);assert(app_firmware_finish()!=ESP_OK);reject_verify=false;
    app_firmware_init();assert(app_firmware_total_free()==0x6c0000-5*65536);
    reject_commit=true;assert(app_firmware_clear(0)!=ESP_OK);assert(app_firmware_begin(6,512,"blocked")!=ESP_OK);reject_commit=false;app_firmware_init();
    app_firmware_slot_info_t info;app_firmware_slot_info(0,&info);assert(info.present);
    assert(app_firmware_clear(0)==ESP_OK);app_firmware_init();assert(app_firmware_total_free()==0x6c0000-4*65536);
    install(0);assert(app_firmware_prepare_boot(0)==ESP_OK);assert(app_firmware_begin(6,512,"blocked")!=ESP_OK);
    bootloader_after_init();assert(boot_count==1&&play_range_valid(boot_address,512));bootloader_after_init();assert(boot_count==1);
    app_firmware_init();assert(app_firmware_prepare_boot(0)==ESP_OK);flash[PLAY_BOOT_ADDRESS+12]^=1;bootloader_after_init();assert(boot_count==1);
    app_firmware_init();assert(app_firmware_prepare_boot(0)==ESP_OK);reject_erase=true;bootloader_after_init();assert(boot_count==1);reject_erase=false;
    reject_verify=true;bootloader_after_init();assert(boot_count==1);reject_verify=false;
    reset();exists=false;legacy=true;flash[PLAY_POOL_A]=flash[0x2a0000]=flash[PLAY_POOL_B]=0xe9;app_firmware_init();app_firmware_slot_info(0,&info);assert(info.present&&info.image_size==512);assert(app_firmware_total_free()==0x6c0000-3*65536);assert(erased==0);
    blob[0]^=1;app_firmware_init();before=erased;assert(app_firmware_begin(1,512,"blocked")!=ESP_OK);assert(erased==before);
    puts("firmware lifecycle: >3 installs, cancellation/reboot, verification failure, atomic catalog failure, delete/reuse, one-shot boot, CRC, flash failure, legacy migration, corrupt catalog passed");
}
