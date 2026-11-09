<div align="center">

<img src="docs/logo.png" width="132" alt="Shizuku Next">

# Shizuku-Next

Một fork nâng cao của Shizuku cho phép các ứng dụng sử dụng API hệ thống thông qua ADB hoặc root.

<img width="200" alt="Màn hình chính" src="docs/screenshots/home.png">
<img width="200" alt="Màn hình Ứng dụng" src="docs/screenshots/apps.png">
<img width="200" alt="Màn hình Cài đặt" src="docs/screenshots/settings.png">

[![Stars](https://img.shields.io/github/stars/rushiranpise/Shizuku-Next?style=for-the-badge)](https://github.com/rushiranpise/Shizuku-Next/stargazers)
[![Downloads](https://img.shields.io/github/downloads/rushiranpise/Shizuku-Next/total?style=for-the-badge)](https://github.com/rushiranpise/Shizuku-Next/releases)
[![Latest Stable](https://img.shields.io/github/v/release/rushiranpise/Shizuku-Next?style=for-the-badge&label=Latest%20Stable)](https://github.com/rushiranpise/Shizuku-Next/releases/latest)
[![Latest Beta](https://img.shields.io/github/v/release/rushiranpise/Shizuku-Next?include_prereleases&style=for-the-badge&label=Latest%20Beta)](https://github.com/rushiranpise/Shizuku-Next/releases)
[![Issues](https://img.shields.io/github/issues/rushiranpise/Shizuku-Next?style=for-the-badge)](https://github.com/rushiranpise/Shizuku-Next/issues)
[![Crowdin](https://img.shields.io/badge/Translate-Crowdin-2e3340?style=for-the-badge&logo=crowdin)](https://crowdin.com/project/Shizuku-Next)

</div>

## Giới thiệu

Shizuku-Next tiếp tục phát triển [fork Shizuku của thedjchi](https://github.com/thedjchi/Shizuku), dựa trên [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku) gốc.

Ứng dụng giữ nguyên tên gói và API của Shizuku, nên các ứng dụng tương thích vẫn hoạt động bình thường.

> **Quan trọng:** Fork này sử dụng khóa ký khác. Hãy gỡ cài đặt Shizuku chính thức hoặc fork của thedjchi trước khi cài Shizuku-Next. Máy chủ được khởi động bởi ứng dụng trước đó có thể vẫn đang chạy cho đến khi bị dừng hoặc thiết bị được khởi động lại.

Nếu bạn muốn sử dụng ứng dụng gốc, hãy truy cập [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku).

## Tải xuống

Tải bản dựng mới nhất từ [Releases](https://github.com/rushiranpise/Shizuku-Next/releases).

## Tính năng

### Khởi động và độ tin cậy
- Khởi động thông qua gỡ lỗi không dây, gỡ lỗi USB, root, hoặc system UID của Samsung (trên các thiết bị hỗ trợ).
- Tự động xử lý ghép nối gỡ lỗi không dây, bao gồm đọc mã ghép nối trên các thiết bị hỗ trợ.
- Tùy chọn khởi động cùng hệ thống, watchdog khởi động lại, phục hồi tự động và cấu hình thời gian khởi động lại.
- Chế độ TCP, phục hồi cổng USB và khởi động thử nghiệm không cần Wi-Fi.
- Tùy chọn ẩn tùy chọn Nhà phát triển và cài đặt ADB.
- Trạng thái khởi động, chẩn đoán và nhật ký lỗi rõ ràng hơn.

### Công cụ quản lý
- Giao diện Material 3 với các tab Trang chủ, Ứng dụng, Module, Labs và Cài đặt.
- Quản lý quyền truy cập Shizuku, quyền ứng dụng, app ops, cài đặt pin và quyền truy cập mạng của ứng dụng, với phương án dự phòng sử dụng API tường lửa của hệ thống trên các ROM thiếu lệnh connectivity.
- Xem chi tiết ứng dụng và khởi chạy activity.
- Shell tích hợp, trình xem nhật ký, trình duyệt tệp và trình cài đặt ứng dụng.
- Quản lý mạng Wi-Fi đã lưu và xuất/nhập thông tin đăng nhập.
- Buộc chế độ tối và ẩn một số cài đặt thiết bị cho các ứng dụng được chọn.
- Thay đổi quyền hàng loạt, danh sách ứng dụng có thể tìm kiếm và thông tin thiết bị.
- Hỗ trợ Tasker và các công cụ tự động hóa khác thông qua intent và broadcast trạng thái.

### Module và tương thích
- Hỗ trợ module ADB thử nghiệm, bao gồm hành động module, dịch vụ và WebUI. [Viết module](docs/adb-modules.md) mô tả định dạng: `module.prop`, môi trường chạy script, cầu nối WebUI, độ tin cậy và cách module được tìm thấy.
- Chế độ ẩn danh và cập nhật trong ứng dụng.
- Hỗ trợ giao diện thân thiện với Android TV và VR.
- Sửa lỗi tương thích MediaTek và cải tiến cho một số thiết bị.

Một số tính năng mang tính thử nghiệm hoặc phụ thuộc vào thiết bị. Phương thức khởi động bằng root, system UID của Samsung và không cần Wi-Fi không khả dụng trên mọi thiết bị. Chỉ cài đặt các module và script mà bạn tin tưởng.

## Yêu cầu

- **Android 7.0 trở lên**
- **Gỡ lỗi không dây:** Android 11 trở lên; một số Android TV được hỗ trợ cũng tương thích.
- **Khởi động qua USB/PC:** Máy tính có ADB.
- **Khởi động bằng root:** Thiết bị đã root với quyền root hoạt động.
- **Khởi động bằng system UID:** Chỉ trên các thiết bị Samsung được hỗ trợ.
- **Khởi động cùng hệ thống:** Chỉ với các phương thức khởi động được hỗ trợ.

Xem [wiki](https://github.com/thedjchi/Shizuku/wiki) để biết hướng dẫn thiết lập và khắc phục sự cố.

## Quyền riêng tư

- Không theo dõi hoặc phân tích.
- Không có telemetry hoặc thư viện độc quyền.
- Không phụ thuộc Google Play Services.
- Mã nguồn mở và bản dựng có thể tái tạo.
- Truy cập Internet được sử dụng cho kết nối gỡ lỗi không dây, kiểm tra và tải cập nhật từ GitHub, cũng như danh sách module (được đọc từ GitHub).

Ứng dụng yêu cầu các quyền cho các tính năng như thông báo, dịch vụ nền, gỡ lỗi không dây và khởi động cùng hệ thống. Xem trang quyền của ứng dụng và mã nguồn để biết chi tiết.

## Bản dịch

Hãy giúp dịch Shizuku-Next trên [Crowdin](https://crowdin.com/project/Shizuku-Next). Xem [`docs/translations.md`](docs/translations.md) để biết chi tiết về bản dịch.

## Ủng hộ dự án

Shizuku-Next hoàn toàn miễn phí và không có quảng cáo. Nếu bạn muốn ủng hộ việc phát triển, bạn có thể [mời mình một ly cà phê](https://www.buymeacoffee.com/rushiranpise).

Hãy cân nhắc ủng hộ cả các dự án gốc: [thedjchi](https://www.buymeacoffee.com/thedjchi) và [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku).

## Hướng dẫn dành cho nhà phát triển

### API

Tài liệu API và dự án demo có sẵn trong [kho lưu trữ Shizuku-API](https://github.com/thedjchi/Shizuku-API).

Shizuku có thể chạy với các mức đặc quyền khác nhau tùy theo phương thức khởi động. Hãy kiểm tra UID và quyền của máy chủ trước khi giả định có quyền root. Nên ưu tiên sử dụng `ShizukuBinderWrapper` thay vì gọi trực tiếp `transactRemote` khi có thể.

### Build

```bash
git clone --recurse-submodules https://github.com/rushiranpise/Shizuku-Next.git
cd Shizuku-Next
./gradlew :manager:assembleDebug
```

Để build bản phát hành, chạy:

```bash
./gradlew :manager:assembleRelease
```

Tác vụ debug sẽ build máy chủ có thể gỡ lỗi. Trong Android Studio, hãy bật **Always install with package manager** trong cấu hình Run/Debug khi gỡ lỗi `shizuku_server`.

### Đóng góp

1. Fork kho lưu trữ.
2. Tạo nhánh tính năng.
3. Thực hiện và kiểm thử các thay đổi của bạn.
4. Commit và đẩy nhánh lên.
5. Mở pull request.

## Ghi nhận

- [RikkaW / RikkaApps](https://github.com/RikkaApps/Shizuku) - Shizuku gốc, máy chủ và API.
- [thedjchi](https://github.com/thedjchi/Shizuku) - fork gốc và nhiều tính năng được kế thừa.
- [kerneldroid](https://github.com/kerneldroid) - [Nightzuku](https://github.com/kerneldroid/Nightzuku), nơi tính năng module ADB được lấy từ: định dạng module, hành động và dịch vụ, cũng như cầu nối WebUI `window.Shizuku`.
- [Shevery](https://github.com/HmnDev-Tech/shevery) - API module đã xuất bản và hướng dẫn mà hỗ trợ module của ứng dụng này dựa theo, cũng như danh mục module mà nó tìm kiếm.
- [ShizuWall](https://github.com/AhmetCanArslan/ShizuWall) (AhmetCanArslan) - phương án dự phòng tường lửa sang API hệ thống khi ROM không có lệnh connectivity, và kỹ thuật đằng sau: giao diện được truy cập như Shizuku với binder được bao bọc, thông qua reflection.
- [Xhizuku](https://github.com/xeonleonreal/Xhizuku) - triển khai khác của định dạng đó, tài liệu được dùng để đối chiếu.
- [KernelSU-Next](https://github.com/KernelSU-Next/KernelSU-Next) - bố cục thẻ, chip và tab mà danh sách module và giao diện Material 3 tuân theo.
- [MMRL](https://github.com/MMRLApp/MMRL) - thành phần nhãn mà màu nhấn mặc định của chip kích thước trên thẻ module tuân theo.
- Tất cả những người đóng góp upstream và các dịch giả trên Crowdin.

## Giấy phép

Được cấp phép theo [Apache License 2.0](LICENSE).

## Lịch sử Star

<a href="https://www.star-history.com/?repos=rushiranpise%2Fshizuku-next&type=date&legend=top-left">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=rushiranpise/shizuku-next&type=date&theme=dark&legend=top-left" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=rushiranpise/shizuku-next&type=date&legend=top-left" />
   <img alt="Star History Chart" src="https://api.star-history.com/chart?repos=rushiranpise/shizuku-next&type=date&legend=top-left" />
 </picture>
</a>
