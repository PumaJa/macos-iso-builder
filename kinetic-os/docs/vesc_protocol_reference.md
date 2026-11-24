# VESC Firmware Protocol Reference

Source: official `vedderb/bldc` repository on GitHub (`master` branch), fetched 2026-11-24.
Raw files saved under `/tmp/claude-0/vesc_src/`: `datatypes.h`, `commands.c` (from `comm/commands.c`), `conf_general.h`.

Notes on what is and is not verified here:
- Payload scaling is quoted from the `buffer_append_*` / `buffer_get_*` calls in `commands.c`. The byte-level encoding inside `buffer_append_float16`, `buffer_append_float32`, `buffer_append_float32_auto` and the `buffer_get_*` byte order live in `buffer.c`, which was not part of the requested files, so they are not verified here.
- The `COMM_BMS_GET_VALUES` reply is built in `bms.c` (via `bms_process_cmd`), which was not fetched. It is listed as dispatched only.

## 1. Firmware version (`conf_general.h`)

```c
#define FW_VERSION_MAJOR			7
#define FW_VERSION_MINOR			01
// Set to 0 for building a release and iterate during beta test builds
#define FW_TEST_VERSION_NUMBER		1
```

- `FW_VERSION_MAJOR` = 7
- `FW_VERSION_MINOR` = 1 (written `01` in C; leading zero makes it an octal literal, value is still 1)
- `FW_TEST_VERSION_NUMBER` = 1 (non-zero, so this is a beta/test build)

**Firmware version: 7.01 (test version 1), i.e. 7.1 beta 1.**

## 2. COMM_PACKET_ID enum (`datatypes.h`, lines 970-1159)

The enum has 157 active entries (values 0-152 and 156-159). Every entry has an explicit value in the source. The three `COMM_PINLOCK*` placeholders (153-155) are commented out, so they are not listed. The values are taken as written, not computed.

| Value | Name |
|---|---|
| 0 | COMM_FW_VERSION |
| 1 | COMM_JUMP_TO_BOOTLOADER |
| 2 | COMM_ERASE_NEW_APP |
| 3 | COMM_WRITE_NEW_APP_DATA |
| 4 | COMM_GET_VALUES |
| 5 | COMM_SET_DUTY |
| 6 | COMM_SET_CURRENT |
| 7 | COMM_SET_CURRENT_BRAKE |
| 8 | COMM_SET_RPM |
| 9 | COMM_SET_POS |
| 10 | COMM_SET_HANDBRAKE |
| 11 | COMM_SET_DETECT |
| 12 | COMM_SET_SERVO_POS |
| 13 | COMM_SET_MCCONF |
| 14 | COMM_GET_MCCONF |
| 15 | COMM_GET_MCCONF_DEFAULT |
| 16 | COMM_SET_APPCONF |
| 17 | COMM_GET_APPCONF |
| 18 | COMM_GET_APPCONF_DEFAULT |
| 19 | COMM_SAMPLE_PRINT |
| 20 | COMM_TERMINAL_CMD |
| 21 | COMM_PRINT |
| 22 | COMM_ROTOR_POSITION |
| 23 | COMM_EXPERIMENT_SAMPLE |
| 24 | COMM_DETECT_MOTOR_PARAM |
| 25 | COMM_DETECT_MOTOR_R_L |
| 26 | COMM_DETECT_MOTOR_FLUX_LINKAGE |
| 27 | COMM_DETECT_ENCODER |
| 28 | COMM_DETECT_HALL_FOC |
| 29 | COMM_REBOOT |
| 30 | COMM_ALIVE |
| 31 | COMM_GET_DECODED_PPM |
| 32 | COMM_GET_DECODED_ADC |
| 33 | COMM_GET_DECODED_CHUK |
| 34 | COMM_FORWARD_CAN |
| 35 | COMM_SET_CHUCK_DATA |
| 36 | COMM_CUSTOM_APP_DATA |
| 37 | COMM_NRF_START_PAIRING |
| 38 | COMM_GPD_SET_FSW |
| 39 | COMM_GPD_BUFFER_NOTIFY |
| 40 | COMM_GPD_BUFFER_SIZE_LEFT |
| 41 | COMM_GPD_FILL_BUFFER |
| 42 | COMM_GPD_OUTPUT_SAMPLE |
| 43 | COMM_GPD_SET_MODE |
| 44 | COMM_GPD_FILL_BUFFER_INT8 |
| 45 | COMM_GPD_FILL_BUFFER_INT16 |
| 46 | COMM_GPD_SET_BUFFER_INT_SCALE |
| 47 | COMM_GET_VALUES_SETUP |
| 48 | COMM_SET_MCCONF_TEMP |
| 49 | COMM_SET_MCCONF_TEMP_SETUP |
| 50 | COMM_GET_VALUES_SELECTIVE |
| 51 | COMM_GET_VALUES_SETUP_SELECTIVE |
| 52 | COMM_EXT_NRF_PRESENT |
| 53 | COMM_EXT_NRF_ESB_SET_CH_ADDR |
| 54 | COMM_EXT_NRF_ESB_SEND_DATA |
| 55 | COMM_EXT_NRF_ESB_RX_DATA |
| 56 | COMM_EXT_NRF_SET_ENABLED |
| 57 | COMM_DETECT_MOTOR_FLUX_LINKAGE_OPENLOOP |
| 58 | COMM_DETECT_APPLY_ALL_FOC |
| 59 | COMM_JUMP_TO_BOOTLOADER_ALL_CAN |
| 60 | COMM_ERASE_NEW_APP_ALL_CAN |
| 61 | COMM_WRITE_NEW_APP_DATA_ALL_CAN |
| 62 | COMM_PING_CAN |
| 63 | COMM_APP_DISABLE_OUTPUT |
| 64 | COMM_TERMINAL_CMD_SYNC |
| 65 | COMM_GET_IMU_DATA |
| 66 | COMM_BM_CONNECT |
| 67 | COMM_BM_ERASE_FLASH_ALL |
| 68 | COMM_BM_WRITE_FLASH |
| 69 | COMM_BM_REBOOT |
| 70 | COMM_BM_DISCONNECT |
| 71 | COMM_BM_MAP_PINS_DEFAULT |
| 72 | COMM_BM_MAP_PINS_NRF5X |
| 73 | COMM_ERASE_BOOTLOADER |
| 74 | COMM_ERASE_BOOTLOADER_ALL_CAN |
| 75 | COMM_PLOT_INIT |
| 76 | COMM_PLOT_DATA |
| 77 | COMM_PLOT_ADD_GRAPH |
| 78 | COMM_PLOT_SET_GRAPH |
| 79 | COMM_GET_DECODED_BALANCE |
| 80 | COMM_BM_MEM_READ |
| 81 | COMM_WRITE_NEW_APP_DATA_LZO |
| 82 | COMM_WRITE_NEW_APP_DATA_ALL_CAN_LZO |
| 83 | COMM_BM_WRITE_FLASH_LZO |
| 84 | COMM_SET_CURRENT_REL |
| 85 | COMM_CAN_FWD_FRAME |
| 86 | COMM_SET_BATTERY_CUT |
| 87 | COMM_SET_BLE_NAME |
| 88 | COMM_SET_BLE_PIN |
| 89 | COMM_SET_CAN_MODE |
| 90 | COMM_GET_IMU_CALIBRATION |
| 91 | COMM_GET_MCCONF_TEMP |
| 92 | COMM_GET_CUSTOM_CONFIG_XML |
| 93 | COMM_GET_CUSTOM_CONFIG |
| 94 | COMM_GET_CUSTOM_CONFIG_DEFAULT |
| 95 | COMM_SET_CUSTOM_CONFIG |
| 96 | COMM_BMS_GET_VALUES |
| 97 | COMM_BMS_SET_CHARGE_ALLOWED |
| 98 | COMM_BMS_SET_BALANCE_OVERRIDE |
| 99 | COMM_BMS_RESET_COUNTERS |
| 100 | COMM_BMS_FORCE_BALANCE |
| 101 | COMM_BMS_ZERO_CURRENT_OFFSET |
| 102 | COMM_JUMP_TO_BOOTLOADER_HW |
| 103 | COMM_ERASE_NEW_APP_HW |
| 104 | COMM_WRITE_NEW_APP_DATA_HW |
| 105 | COMM_ERASE_BOOTLOADER_HW |
| 106 | COMM_JUMP_TO_BOOTLOADER_ALL_CAN_HW |
| 107 | COMM_ERASE_NEW_APP_ALL_CAN_HW |
| 108 | COMM_WRITE_NEW_APP_DATA_ALL_CAN_HW |
| 109 | COMM_ERASE_BOOTLOADER_ALL_CAN_HW |
| 110 | COMM_SET_ODOMETER |
| 111 | COMM_PSW_GET_STATUS |
| 112 | COMM_PSW_SWITCH |
| 113 | COMM_BMS_FWD_CAN_RX |
| 114 | COMM_BMS_HW_DATA |
| 115 | COMM_GET_BATTERY_CUT |
| 116 | COMM_BM_HALT_REQ |
| 117 | COMM_GET_QML_UI_HW |
| 118 | COMM_GET_QML_UI_APP |
| 119 | COMM_CUSTOM_HW_DATA |
| 120 | COMM_QMLUI_ERASE |
| 121 | COMM_QMLUI_WRITE |
| 122 | COMM_IO_BOARD_GET_ALL |
| 123 | COMM_IO_BOARD_SET_PWM |
| 124 | COMM_IO_BOARD_SET_DIGITAL |
| 125 | COMM_BM_MEM_WRITE |
| 126 | COMM_BMS_BLNC_SELFTEST |
| 127 | COMM_GET_EXT_HUM_TMP |
| 128 | COMM_GET_STATS |
| 129 | COMM_RESET_STATS |
| 130 | COMM_LISP_READ_CODE |
| 131 | COMM_LISP_WRITE_CODE |
| 132 | COMM_LISP_ERASE_CODE |
| 133 | COMM_LISP_SET_RUNNING |
| 134 | COMM_LISP_GET_STATS |
| 135 | COMM_LISP_PRINT |
| 136 | COMM_BMS_SET_BATT_TYPE |
| 137 | COMM_BMS_GET_BATT_TYPE |
| 138 | COMM_LISP_REPL_CMD |
| 139 | COMM_LISP_STREAM_CODE |
| 140 | COMM_FILE_LIST |
| 141 | COMM_FILE_READ |
| 142 | COMM_FILE_WRITE |
| 143 | COMM_FILE_MKDIR |
| 144 | COMM_FILE_REMOVE |
| 145 | COMM_LOG_START |
| 146 | COMM_LOG_STOP |
| 147 | COMM_LOG_CONFIG_FIELD |
| 148 | COMM_LOG_DATA_F32 |
| 149 | COMM_SET_APPCONF_NO_STORE |
| 150 | COMM_GET_GNSS |
| 151 | COMM_LOG_DATA_F64 |
| 152 | COMM_LISP_RMSG |
| 156 | COMM_SHUTDOWN |
| 157 | COMM_FW_INFO |
| 158 | COMM_CAN_UPDATE_BAUD_ALL |
| 159 | COMM_MOTOR_ESTOP |

Note: `COMM_SHUTDOWN` = 156 follows the commented-out pinlock placeholders, which is why the values skip 153-155.

## 3. Payload layouts (`comm/commands.c`)

General rules visible in `commands.c`:
- Every reply starts with the packet ID byte (`send_buffer[ind++] = packet_id;` or the explicit `COMM_*` id).
- `float16` fields are written with `buffer_append_float16(buf, value, scale, &ind)`. `float32` with `buffer_append_float32(buf, value, scale, &ind)`. The `scale` argument is the multiplier used when encoding. `float32_auto` takes no scale argument.
- Selective commands carry a bitmask. Bit N selects the N-th field listed below, in the order the `if (mask & (1 << N))` blocks appear.

### 3.1 COMM_FW_VERSION (0) reply

Verbatim source (`commands.c` lines 231-295):

```c
	case COMM_FW_VERSION: {
		int32_t ind = 0;
		uint8_t send_buffer[65];
		send_buffer[ind++] = COMM_FW_VERSION;
		send_buffer[ind++] = FW_VERSION_MAJOR;
		send_buffer[ind++] = FW_VERSION_MINOR;

		strcpy((char*)(send_buffer + ind), HW_NAME);
		ind += strlen(HW_NAME) + 1;

		memcpy(send_buffer + ind, STM32_UUID_8, 12);
		ind += 12;

		// Add 1 to the UUID for the second motor, so that configuration backup and
		// restore works.
		if (mc_interface_get_motor_thread() == 2) {
			send_buffer[ind - 1]++;
		}

		send_buffer[ind++] = app_get_configuration()->pairing_done;
		send_buffer[ind++] = FW_TEST_VERSION_NUMBER;

		send_buffer[ind++] = HW_TYPE_VESC;

		send_buffer[ind++] = conf_custom_cfg_num();

#ifdef HW_HAS_PHASE_FILTERS
		send_buffer[ind++] = 1;
#else
		send_buffer[ind++] = 0;
#endif

#ifdef QMLUI_SOURCE_HW
#ifdef QMLUI_HW_FULLSCREEN
		send_buffer[ind++] = 2;
#else
		send_buffer[ind++] = 1;
#endif
#else
		send_buffer[ind++] = 0;
#endif

#ifdef QMLUI_SOURCE_APP
#ifdef QMLUI_APP_FULLSCREEN
		send_buffer[ind++] = 2;
#else
		send_buffer[ind++] = 1;
#endif
#else
		if (flash_helper_code_data(CODE_IND_QML)) {
			send_buffer[ind++] = flash_helper_code_flags(CODE_IND_QML);
		} else {
			send_buffer[ind++] = 0;
		}
#endif
		send_buffer[ind++] = nrf_flags;

		strcpy((char*)(send_buffer + ind), FW_NAME);
		ind += strlen(FW_NAME) + 1;

		buffer_append_uint32(send_buffer, main_calc_hw_crc(), &ind);

		fw_version_sent_cnt++;

		reply_func(send_buffer, ind);
	} break;
```

Reply layout, in order:

| # | Field | Type | Notes |
|---|---|---|---|
| 0 | packet id | uint8 | `COMM_FW_VERSION` = 0 |
| 1 | `FW_VERSION_MAJOR` | uint8 | 7 |
| 2 | `FW_VERSION_MINOR` | uint8 | 1 |
| 3 | `HW_NAME` | NUL-terminated string | `strcpy` + `strlen+1` |
| 4 | `STM32_UUID_8` | 12 bytes | last byte incremented by 1 for motor thread 2 (dual-motor) |
| 5 | `pairing_done` | uint8 | from `app_get_configuration()` |
| 6 | `FW_TEST_VERSION_NUMBER` | uint8 | 1 |
| 7 | `HW_TYPE_VESC` | uint8 | |
| 8 | `conf_custom_cfg_num()` | uint8 | |
| 9 | phase filters flag | uint8 | 1 if `HW_HAS_PHASE_FILTERS`, else 0 |
| 10 | QML HW flag | uint8 | 0, 1 or 2 (`QMLUI_HW_FULLSCREEN`), or flash-helper flags |
| 11 | QML APP flag | uint8 | 0, 1 or 2 (`QMLUI_APP_FULLSCREEN`), or flash-helper flags |
| 12 | `nrf_flags` | uint8 | |
| 13 | `FW_NAME` | NUL-terminated string | `strcpy` + `strlen+1` |
| 14 | `main_calc_hw_crc()` | uint32 | `buffer_append_uint32` |

Field 13-14 and the variable-length strings mean the total length depends on `HW_NAME` and `FW_NAME`.

### 3.2 COMM_GET_VALUES (4) and COMM_GET_VALUES_SELECTIVE (50) reply

The request for `COMM_GET_VALUES` carries no mask; the handler uses `mask = 0xFFFFFFFF`, so every field below is sent.
For `COMM_GET_VALUES_SELECTIVE` the request carries a `uint32` mask (`buffer_get_uint32(data, &ind2)`), and the reply echoes that mask as a `uint32` immediately after the packet id, followed by the selected fields.

Verbatim source (`commands.c`, `case COMM_GET_VALUES:` through the end of its block):

```c
	case COMM_GET_VALUES:
	case COMM_GET_VALUES_SELECTIVE: {
		int32_t ind = 0;
		uint8_t *send_buffer = mempools_get_packet_buffer();
		send_buffer[ind++] = packet_id;

		uint32_t mask = 0xFFFFFFFF;
		if (packet_id == COMM_GET_VALUES_SELECTIVE) {
			int32_t ind2 = 0;
			mask = buffer_get_uint32(data, &ind2);
			buffer_append_uint32(send_buffer, mask, &ind);
		}

		if (mask & ((uint32_t)1 << 0)) {
			buffer_append_float16(send_buffer, mc_interface_temp_fet_filtered(), 1e1, &ind);
		}
		if (mask & ((uint32_t)1 << 1)) {
			buffer_append_float16(send_buffer, mc_interface_temp_motor_filtered(), 1e1, &ind);
		}
		if (mask & ((uint32_t)1 << 2)) {
			buffer_append_float32(send_buffer, mc_interface_read_reset_avg_motor_current(), 1e2, &ind);
		}
		if (mask & ((uint32_t)1 << 3)) {
			buffer_append_float32(send_buffer, mc_interface_read_reset_avg_input_current(), 1e2, &ind);
		}
		if (mask & ((uint32_t)1 << 4)) {
			buffer_append_float32(send_buffer, mc_interface_read_reset_avg_id(), 1e2, &ind);
		}
		if (mask & ((uint32_t)1 << 5)) {
			buffer_append_float32(send_buffer, mc_interface_read_reset_avg_iq(), 1e2, &ind);
		}
		if (mask & ((uint32_t)1 << 6)) {
			buffer_append_float16(send_buffer, mc_interface_get_duty_cycle_now(), 1e3, &ind);
		}
		if (mask & ((uint32_t)1 << 7)) {
			buffer_append_float32(send_buffer, mc_interface_get_rpm(), 1e0, &ind);
		}
		if (mask & ((uint32_t)1 << 8)) {
			buffer_append_float16(send_buffer, mc_interface_get_input_voltage_filtered(), 1e1, &ind);
		}
		if (mask & ((uint32_t)1 << 9)) {
			buffer_append_float32(send_buffer, mc_interface_get_amp_hours(false), 1e4, &ind);
		}
		if (mask & ((uint32_t)1 << 10)) {
			buffer_append_float32(send_buffer, mc_interface_get_amp_hours_charged(false), 1e4, &ind);
		}
		if (mask & ((uint32_t)1 << 11)) {
			buffer_append_float32(send_buffer, mc_interface_get_watt_hours(false), 1e4, &ind);
		}
		if (mask & ((uint32_t)1 << 12)) {
			buffer_append_float32(send_buffer, mc_interface_get_watt_hours_charged(false), 1e4, &ind);
		}
		if (mask & ((uint32_t)1 << 13)) {
			buffer_append_int32(send_buffer, mc_interface_get_tachometer_value(false), &ind);
		}
		if (mask & ((uint32_t)1 << 14)) {
			buffer_append_int32(send_buffer, mc_interface_get_tachometer_abs_value(false), &ind);
		}
		if (mask & ((uint32_t)1 << 15)) {
			send_buffer[ind++] = mc_interface_get_fault();
		}
		if (mask & ((uint32_t)1 << 16)) {
			buffer_append_float32(send_buffer, mc_interface_get_pid_pos_now(), 1e6, &ind);
		}
		if (mask & ((uint32_t)1 << 17)) {
			uint8_t current_controller_id = app_get_configuration()->controller_id;
#ifdef HW_HAS_DUAL_MOTORS
			if (mc_interface_get_motor_thread() == 2) {
				current_controller_id = utils_second_motor_id();
			}
#endif
			send_buffer[ind++] = current_controller_id;
		}
		if (mask & ((uint32_t)1 << 18)) {
			if (mc_interface_get_motor_thread() == 2) {
				buffer_append_float16(send_buffer, NTC_TEMP_MOS1_M2(), 1e1, &ind);
				buffer_append_float16(send_buffer, NTC_TEMP_MOS2_M2(), 1e1, &ind);
				buffer_append_float16(send_buffer, NTC_TEMP_MOS3_M2(), 1e1, &ind);
			} else {
				buffer_append_float16(send_buffer, NTC_TEMP_MOS1(), 1e1, &ind);
				buffer_append_float16(send_buffer, NTC_TEMP_MOS2(), 1e1, &ind);
				buffer_append_float16(send_buffer, NTC_TEMP_MOS3(), 1e1, &ind);
			}
		}
		if (mask & ((uint32_t)1 << 19)) {
			buffer_append_float32(send_buffer, mc_interface_read_reset_avg_vd(), 1e3, &ind);
		}
		if (mask & ((uint32_t)1 << 20)) {
			buffer_append_float32(send_buffer, mc_interface_read_reset_avg_vq(), 1e3, &ind);
		}
		if (mask & ((uint32_t)1 << 21)) {
			uint8_t status = 0;
			status |= timeout_has_timeout();
			status |= timeout_kill_sw_active() << 1;
			send_buffer[ind++] = status;
		}

		reply_func(send_buffer, ind);
		mempools_free_packet_buffer(send_buffer);
	} break;
```

Field table (bit N = `1 << N`, order = reply order):

| Bit | Field (source call) | Wire type | Scale arg |
|---|---|---|---|
| 0 | `mc_interface_temp_fet_filtered()` | float16 | 1e1 |
| 1 | `mc_interface_temp_motor_filtered()` | float16 | 1e1 |
| 2 | `mc_interface_read_reset_avg_motor_current()` | float32 | 1e2 |
| 3 | `mc_interface_read_reset_avg_input_current()` | float32 | 1e2 |
| 4 | `mc_interface_read_reset_avg_id()` | float32 | 1e2 |
| 5 | `mc_interface_read_reset_avg_iq()` | float32 | 1e2 |
| 6 | `mc_interface_get_duty_cycle_now()` | float16 | 1e3 |
| 7 | `mc_interface_get_rpm()` | float32 | 1e0 |
| 8 | `mc_interface_get_input_voltage_filtered()` | float16 | 1e1 |
| 9 | `mc_interface_get_amp_hours(false)` | float32 | 1e4 |
| 10 | `mc_interface_get_amp_hours_charged(false)` | float32 | 1e4 |
| 11 | `mc_interface_get_watt_hours(false)` | float32 | 1e4 |
| 12 | `mc_interface_get_watt_hours_charged(false)` | float32 | 1e4 |
| 13 | `mc_interface_get_tachometer_value(false)` | int32 | none |
| 14 | `mc_interface_get_tachometer_abs_value(false)` | int32 | none |
| 15 | `mc_interface_get_fault()` | uint8 | none (`send_buffer[ind++]`) |
| 16 | `mc_interface_get_pid_pos_now()` | float32 | 1e6 |
| 17 | controller id (`app_get_configuration()->controller_id`) | uint8 | none |
| 18 | `NTC_TEMP_MOS1`, `MOS2`, `MOS3` (or `_M2` variants on motor 2) | 3 x float16 | 1e1 each |
| 19 | `mc_interface_read_reset_avg_vd()` | float32 | 1e3 |
| 20 | `mc_interface_read_reset_avg_vq()` | float32 | 1e3 |
| 21 | status byte: bit0 = `timeout_has_timeout()`, bit1 = `timeout_kill_sw_active()` | uint8 | none |

### 3.3 COMM_GET_VALUES_SETUP (47) and COMM_GET_VALUES_SETUP_SELECTIVE (51) reply

Same mask convention as 3.2 (SELECTIVE takes a `uint32` mask and echoes it). Field table from `commands.c` lines 797-~902:

| Bit | Field | Wire type | Scale arg |
|---|---|---|---|
| 0 | `mc_interface_temp_fet_filtered()` | float16 | 1e1 |
| 1 | `mc_interface_temp_motor_filtered()` | float16 | 1e1 |
| 2 | `val.current_tot` | float32 | 1e2 |
| 3 | `val.current_in_tot` | float32 | 1e2 |
| 4 | `mc_interface_get_duty_cycle_now()` | float16 | 1e3 |
| 5 | `mc_interface_get_rpm()` | float32 | 1e0 |
| 6 | `mc_interface_get_speed()` | float32 | 1e3 |
| 7 | `mc_interface_get_input_voltage_filtered()` | float16 | 1e1 |
| 8 | `battery_level` | float16 | 1e3 |
| 9 | `val.ah_tot` | float32 | 1e4 |
| 10 | `val.ah_charge_tot` | float32 | 1e4 |
| 11 | `val.wh_tot` | float32 | 1e4 |
| 12 | `val.wh_charge_tot` | float32 | 1e4 |
| 13 | `mc_interface_get_distance()` | float32 | 1e3 |
| 14 | `mc_interface_get_distance_abs()` | float32 | 1e3 |
| 15 | `mc_interface_get_pid_pos_now()` | float32 | 1e6 |
| 16 | `mc_interface_get_fault()` | uint8 | none |
| 17 | controller id | uint8 | none |
| 18 | `val.num_vescs` | uint8 | none |
| 19 | `wh_batt_left` | float32 | 1e3 |
| 20 | `mc_interface_get_odometer()` | uint32 | none |
| 21 | system time in ms (`chVTGetSystemTimeX() / (CH_CFG_ST_FREQUENCY / 1000)`) | uint32 | none |

### 3.4 COMM_GET_IMU_DATA (65) reply

Request: `uint16` mask (`buffer_get_uint16`). Reply: packet id, `uint16` mask echoed (`buffer_append_uint16`), then each selected value as `float32_auto` (no scale argument). Bit map from `commands.c` lines 1106-~1212:

| Bit | Value |
|---|---|
| 0, 1, 2 | `rpy[0]`, `rpy[1]`, `rpy[2]` |
| 3, 4, 5 | `acc[0]`, `acc[1]`, `acc[2]` |
| 6, 7, 8 | `gyro[0]`, `gyro[1]`, `gyro[2]` |
| 9, 10, 11 | `mag[0]`, `mag[1]`, `mag[2]` |
| 12, 13, 14, 15 | `q[0]`, `q[1]`, `q[2]`, `q[3]` |

### 3.5 COMM_SET_CURRENT (6)

Request payload: one `int32`, decoded with `buffer_get_int32`, then `mc_interface_set_current((float)value / 1000.0)`. Units: amperes at 1/1000 resolution (value in mA). Calls `timeout_reset()`. No reply.

### 3.6 COMM_SET_CURRENT_BRAKE (7)

Request payload: one `int32`, `mc_interface_set_brake_current((float)value / 1000.0)`. Units: amperes at 1/1000 resolution. Calls `timeout_reset()`. No reply.

(Related, for completeness: `COMM_SET_CURRENT_REL` (84) takes a `float32` with `buffer_get_float32(data, 1e5, &ind)` and calls `mc_interface_set_current_rel`. No reply.)

### 3.7 COMM_ALIVE (30)

No payload, no reply. Handler: `SHUTDOWN_RESET(); timeout_reset(); break;`

### 3.8 COMM_FORWARD_CAN (34)

Request payload: `data[0]` = target CAN ID, `data[1..len-1]` = inner packet. If built for dual motors and `data[0] == utils_second_motor_id()`, the inner packet is processed locally on motor thread 2. Otherwise `comm_can_send_buffer(data[0], data + 1, len - 1, 0)` is called. The command itself has no direct reply; any reply to the inner packet is routed through `send_func_can_fwd = reply_func`.

### 3.9 COMM_PING_CAN (62)

Request: no payload. Handler (in the blocking section of `commands.c`, around line 2355):

```c
case COMM_PING_CAN: {
    int32_t ind = 0;
    send_buffer[ind++] = COMM_PING_CAN;
    for (uint8_t i = 0;i < 255;i++) {
        HW_TYPE hw_type;
        if (comm_can_ping(i, &hw_type)) {
            send_buffer[ind++] = i;
        }
    }
    if (send_func_blocking) {
        send_func_blocking(send_buffer, ind);
    }
} break;
```

Reply: byte `62`, followed by one `uint8` per CAN ID (0-254) that answers `comm_can_ping`. Only IDs that respond are listed.

### 3.10 COMM_GET_BATTERY_CUT (115)

Request: no payload. Reply:
1. packet id (uint8, 115)
2. `l_battery_cut_start`, `buffer_append_float32(..., 1e3, ...)`
3. `l_battery_cut_end`, `buffer_append_float32(..., 1e3, ...)`

Source: `commands.c` case at line 1265:
```c
buffer_append_float32(send_buffer, mcconf->l_battery_cut_start, 1e3, &ind);
buffer_append_float32(send_buffer, mcconf->l_battery_cut_end, 1e3, &ind);
```

### 3.11 COMM_BMS_GET_VALUES (96)

Dispatched at `commands.c` line 1303 to `bms_process_cmd(data - 1, len + 1, reply_func)`. The reply layout is defined in `bms.c`, which was not fetched. Payload format is NOT verified here.

### 3.12 COMM_GET_STATS (128)

Request: `uint16` mask (`buffer_get_uint16`). Reply: packet id, `uint32` mask (`buffer_append_uint32`), then each selected value as `float32_auto`:

| Bit | Value |
|---|---|
| 0 | `mc_interface_stat_speed_avg()` |
| 1 | `mc_interface_stat_speed_max()` |
| 2 | `mc_interface_stat_power_avg()` |
| 3 | `mc_interface_stat_power_max()` |
| 4 | `mc_interface_stat_current_avg()` |
| 5 | `mc_interface_stat_current_max()` |
| 6 | `mc_interface_stat_temp_mosfet_avg()` |
| 7 | `mc_interface_stat_temp_mosfet_max()` |
| 8 | `mc_interface_stat_temp_motor_avg()` |
| 9 | `mc_interface_stat_temp_motor_max()` |
| 10 | `mc_interface_stat_count_time()` |

## 4. mc_fault_code enum (`datatypes.h`, lines 101-180)

All entries are unassigned in the source, so values are sequential from 0. Verified by the source listing.

| Value | Name |
|---|---|
| 0 | FAULT_CODE_NONE |
| 1 | FAULT_CODE_OVER_VOLTAGE |
| 2 | FAULT_CODE_UNDER_VOLTAGE |
| 3 | FAULT_CODE_DRV |
| 4 | FAULT_CODE_ABS_OVER_CURRENT |
| 5 | FAULT_CODE_OVER_TEMP_FET |
| 6 | FAULT_CODE_OVER_TEMP_MOTOR |
| 7 | FAULT_CODE_GATE_DRIVER_OVER_VOLTAGE |
| 8 | FAULT_CODE_GATE_DRIVER_UNDER_VOLTAGE |
| 9 | FAULT_CODE_MCU_UNDER_VOLTAGE |
| 10 | FAULT_CODE_BOOTING_FROM_WATCHDOG_RESET |
| 11 | FAULT_CODE_ENCODER_SPI |
| 12 | FAULT_CODE_ENCODER_SINCOS_BELOW_MIN_AMPLITUDE |
| 13 | FAULT_CODE_ENCODER_SINCOS_ABOVE_MAX_AMPLITUDE |
| 14 | FAULT_CODE_FLASH_CORRUPTION |
| 15 | FAULT_CODE_HIGH_OFFSET_CURRENT_SENSOR_1 |
| 16 | FAULT_CODE_HIGH_OFFSET_CURRENT_SENSOR_2 |
| 17 | FAULT_CODE_HIGH_OFFSET_CURRENT_SENSOR_3 |
| 18 | FAULT_CODE_UNBALANCED_CURRENTS |
| 19 | FAULT_CODE_BRK |
| 20 | FAULT_CODE_RESOLVER_LOT |
| 21 | FAULT_CODE_RESOLVER_DOS |
| 22 | FAULT_CODE_RESOLVER_LOS |
| 23 | FAULT_CODE_FLASH_CORRUPTION_APP_CFG |
| 24 | FAULT_CODE_FLASH_CORRUPTION_MC_CFG |
| 25 | FAULT_CODE_ENCODER_NO_MAGNET |
| 26 | FAULT_CODE_ENCODER_MAGNET_TOO_STRONG |
| 27 | FAULT_CODE_PHASE_FILTER |
| 28 | FAULT_CODE_ENCODER_FAULT |
| 29 | FAULT_CODE_LV_OUTPUT_FAULT |
| 30 | FAULT_CODE_ENCODER_SLIP |
| 31 | FAULT_CODE_OVERSPEED |
| 32 | FAULT_CODE_UNDERSPEED |
| 33 | FAULT_CODE_ABS_OVERSPEED |
| 34 | FAULT_CODE_THROTTLE_RANGE |
