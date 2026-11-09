# Chính sách Bảo mật

## Các phiên bản được hỗ trợ

Các bản cập nhật bảo mật **chỉ** được cung cấp cho các phiên bản sau của Shizuku:

| Phiên bản | Được hỗ trợ |
| ------- | --------- |
| Bản phát hành ổn định mới nhất | ✅* |
| Bản debug / prerelease mới nhất | ✅ |
| Các phiên bản cũ hơn | ❌ |

Các báo cáo ảnh hưởng đến các phiên bản không được hỗ trợ có thể bị đóng mà không có hành động nào.

---

## Báo cáo Lỗ hổng

Nếu bạn phát hiện lỗ hổng bảo mật trong Shizuku, vui lòng báo cáo **riêng tư** và thực hành tiết lộ có trách nhiệm. [Xem cách thực hiện](https://docs.github.com/en/code-security/how-tos/report-and-fix-vulnerabilities/report-a-vulnerability/privately-reporting-a-security-vulnerability).

Shizuku cung cấp quyền truy cập ở mức **ADB và/hoặc root**, vì vậy các vấn đề bảo mật có thể có tác động cao.

Khi báo cáo lỗ hổng, bạn có thể bao gồm bất kỳ thông tin nào sau đây:

- Hướng dẫn tái hiện chi tiết từng bước (bao gồm các bước chi tiết hoặc từng bước một)
- Ảnh chụp màn hình hoặc bản ghi màn hình
- Nhật ký hoặc đầu ra sự cố
- Mã proof-of-concept (PoC)
- Ứng dụng kiểm thử hoặc script chứng minh vấn đề

Việc cung cấp thông tin chi tiết giúp chúng tôi tái hiện và khắc phục vấn đề hiệu quả hơn.

---

## Kiểm tra trước khi Báo cáo

Để giảm các báo cáo trùng lặp và kết quả dương tính giả, vui lòng xác minh vấn đề trong các điều kiện sau:

1. Xác nhận vấn đề tái hiện trên **bản phát hành ổn định mới nhất**
2. **Khi có thể, cũng kiểm tra trên bản prerelease (debug) mới nhất**

Nếu vấn đề **không** tái hiện trên bản prerelease, nó có thể đã được khắc phục.

---

## Tiết lộ có Trách nhiệm

Do bản chất đặc quyền của Shizuku, các lỗ hổng có thể dễ dàng bị khai thác nếu được tiết lộ sớm.

Vui lòng tuân theo các hướng dẫn tiết lộ sau:

- **Không** tiết lộ công khai chi tiết lỗ hổng ngay lập tức
- Chờ **ít nhất 1 tháng sau khi phát hành bản chứa bản sửa lỗi** trước khi chia sẻ các chi tiết kỹ thuật có thể dẫn đến việc khai thác một cách hợp lý
  - Điều này bao gồm các bài viết khai thác, kỹ thuật lạm dụng, đoạn mã, video hoặc hướng dẫn chứng minh các cuộc tấn công trong thế giới thực

Thời gian chờ này cho phép người dùng có đủ thời gian để cập nhật và giúp giảm nguy cơ bị khai thác tích cực.

---

## Những gì Không nên Báo cáo

- Các vấn đề chỉ ảnh hưởng đến các phiên bản không được hỗ trợ
- Các lỗ hổng yêu cầu sửa đổi chính Shizuku để có thể bị khai thác
- Các báo cáo không có bất kỳ tác động bảo mật hợp lý nào

---

## Tiền thưởng Lỗi (Bug Bounty)

Shizuku **không** cung cấp tiền thưởng lỗi hoặc phần thưởng bằng tiền.

Tuy nhiên, **các nhà báo cáo bảo mật có trách nhiệm sẽ được ghi nhận trong ghi chú phát hành** hoặc các lời cảm ơn công khai khác, theo quyết định của người bảo trì.

---

## Lời cảm ơn

Chúng tôi đánh giá cao nỗ lực của các nhà nghiên cứu bảo mật và thành viên cộng đồng giúp cải thiện bảo mật của Shizuku. Việc tiết lộ có trách nhiệm giúp giữ an toàn cho người dùng và hệ sinh thái khỏe mạnh.
