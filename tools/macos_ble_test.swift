import Foundation
import CoreBluetooth

final class PassportBleTest: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate {
    private let serviceUUID = CBUUID(string: "4A17D400-34AD-4D7B-93F8-84237AACC001")
    private let writeUUID = CBUUID(string: "4A17D400-34AD-4D7B-93F8-84237AACC002")
    private var central: CBCentralManager!
    private var passport: CBPeripheral?
    private var characteristic: CBCharacteristic?
    private var packet = Data()
    private var offset = 0
    private var retryCount = 0
    private var holdSeconds: TimeInterval = 0
    private var writeCompleted = false

    func run(message: String, messageType: UInt8, holdSeconds: TimeInterval) {
        self.holdSeconds = max(0, holdSeconds)
        var body = Data()
        for character in message {
            let bytes = Data(String(character).utf8)
            if body.count + bytes.count > 160 { break }
            body.append(bytes)
        }
        guard !body.isEmpty else { finish("测试文本为空", code: 2) }
        packet = Data([0xA5, messageType, UInt8(body.count & 0xff), UInt8((body.count >> 8) & 0xff)])
        packet.append(body)
        central = CBCentralManager(delegate: self, queue: .main)
        DispatchQueue.main.asyncAfter(deadline: .now() + 60) { [weak self] in
            guard let self, !self.writeCompleted else { return }
            self.finish("测试超时：未完成扫描、配对或写入", code: 3)
        }
        dispatchMain()
    }

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        guard central.state == .poweredOn else {
            finish("电脑蓝牙不可用，状态=\(central.state.rawValue)", code: 4)
        }
        print("[1/4] 蓝牙已开启，扫描 PassportNotify…")
        central.scanForPeripherals(withServices: nil, options: [CBCentralManagerScanOptionAllowDuplicatesKey: false])
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                        advertisementData: [String : Any], rssi RSSI: NSNumber) {
        let localName = advertisementData[CBAdvertisementDataLocalNameKey] as? String
        guard localName == "PassportNotify" || peripheral.name == "PassportNotify" else { return }
        print("[2/4] 找到 PassportNotify，RSSI=\(RSSI)，开始连接…")
        self.central.stopScan()
        passport = peripheral
        peripheral.delegate = self
        self.central.connect(peripheral, options: nil)
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        print("[3/4] 已连接，发现加密通知服务…")
        peripheral.discoverServices([serviceUUID])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        finish("连接失败：\(error?.localizedDescription ?? "未知错误")", code: 5)
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral,
                        timestamp: CFAbsoluteTime, isReconnecting: Bool, error: Error?) {
        finish("连接中断：\(error?.localizedDescription ?? "无错误信息")", code: 6)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        if let error { finish("发现服务失败：\(error.localizedDescription)", code: 7) }
        guard let service = peripheral.services?.first(where: { $0.uuid == serviceUUID }) else {
            finish("未找到 PassportNotify 服务", code: 8)
        }
        peripheral.discoverCharacteristics([writeUUID], for: service)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        if let error { finish("发现写入特征失败：\(error.localizedDescription)", code: 9) }
        guard let found = service.characteristics?.first(where: { $0.uuid == writeUUID }) else {
            finish("未找到通知写入特征", code: 10)
        }
        characteristic = found
        print("[4/4] 写入需要认证；如系统弹出配对框，请输入设备屏幕六位码。")
        writeNext(peripheral)
    }

    private func writeNext(_ peripheral: CBPeripheral) {
        guard let characteristic else { return }
        if offset >= packet.count {
            guard !writeCompleted else { return }
            writeCompleted = true
            print("PASS：电脑已通过 BLE 写入测试消息")
            if holdSeconds > 0 {
                print("保持加密连接 \(Int(holdSeconds)) 秒，便于观察设备连接状态和屏幕…")
                fflush(stdout)
                DispatchQueue.main.asyncAfter(deadline: .now() + holdSeconds) { [weak self] in
                    self?.finish("电脑 BLE 保持连接测试结束", code: 0)
                }
                return
            }
            finish("电脑 BLE 单次发送测试结束", code: 0)
        }
        let maximum = max(1, peripheral.maximumWriteValueLength(for: .withResponse))
        let end = min(offset + maximum, packet.count)
        peripheral.writeValue(packet.subdata(in: offset..<end), for: characteristic, type: .withResponse)
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        if let error {
            if retryCount < 1 {
                retryCount += 1
                print("首次写入等待安全配对：\(error.localizedDescription)；2 秒后重试…")
                DispatchQueue.main.asyncAfter(deadline: .now() + 2) { [weak self, weak peripheral] in
                    guard let self, let peripheral else { return }
                    self.writeNext(peripheral)
                }
                return
            }
            finish("写入失败：\(error.localizedDescription)", code: 11)
        }
        let maximum = max(1, peripheral.maximumWriteValueLength(for: .withResponse))
        offset = min(offset + maximum, packet.count)
        writeNext(peripheral)
    }

    private func finish(_ text: String, code: Int32) -> Never {
        print(text)
        if let passport { central?.cancelPeripheralConnection(passport) }
        fflush(stdout)
        exit(code)
    }
}

let arguments = Array(CommandLine.arguments.dropFirst())
let message = arguments.first ?? "电脑蓝牙测试 · AI Passport 正常"
let holdSeconds = arguments.count > 1 ? (TimeInterval(arguments[1]) ?? 0) : 0
let messageType: UInt8 = arguments.count > 2 ? ({
    switch arguments[2].lowercased() {
    case "sms": return 0x01
    case "app": return 0x02
    default: return 0x03
    }
})() : 0x03
PassportBleTest().run(message: message, messageType: messageType, holdSeconds: holdSeconds)
