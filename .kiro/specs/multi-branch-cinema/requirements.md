# Requirements Document

## Introduction

Hệ thống quản lý chuỗi rạp chiếu phim đa chi nhánh trên nền JSP/Servlet, phục vụ 5 nhóm người dùng: Admin (quản trị hệ thống toàn chuỗi), Branch Manager (quản lý chi nhánh), Branch Staff (nhân viên chi nhánh — bán vé, soát vé, bán bắp nước, đối soát ca), Customer (khách hàng có tài khoản — đặt vé, tích điểm, ví), và Guest (khách vãng lai — duyệt phim/suất chiếu không cần đăng nhập). Hệ thống thống nhất quản lý danh mục phim, phòng chiếu & sơ đồ ghế, lịch chiếu, đặt vé & giữ ghế, thanh toán/hoàn tiền, voucher/khuyến mãi, soát vé và báo cáo — cùng các nghiệp vụ rạp phim mở rộng: tài khoản khách hàng & lịch sử mua, tích điểm & hạng thành viên, ví/nạp tiền, dịch vụ bắp nước (concession) bán tại quầy và đặt trước online gắn với suất chiếu, kiểm kê kho chi nhánh, đối soát ca bán hàng, và thông báo khách hàng — với luồng kiểm tra (validation) đầy đủ trên mọi thao tác và cơ chế xử lý đồng thời (concurrent flows) nhằm ngăn trùng lịch, đặt ghế đôi và xung đột dữ liệu khi nhiều người dùng thao tác cùng lúc.

## Boundary Context

- **In scope**:
  - Quản lý chi nhánh, phim (Admin quản danh mục), phòng chiếu & sơ đồ ghế theo chi nhánh, lịch chiếu & kiểm tra xung đột, khung giá & tính giá vé, tìm kiếm/hiển thị suất chiếu cho khách, đặt vé với giữ ghế 10 phút, thanh toán (tiền mặt tại quầy / cổng online mock / chuyển khoản xác nhận tay), voucher/promo, hủy vé & hoàn tiền bậc thang, soát vé tại cửa phòng chiếu, phân quyền & phạm vi chi nhánh (branch-scoped RBAC), báo cáo doanh thu & vận hành, kiểm soát đồng thời & toàn vẹn dữ liệu, kiểm tra dữ liệu & xử lý lỗi, nhật ký thao tác (audit log).
  - Nghiệp vụ mở rộng: tài khoản khách hàng & lịch sử mua, chương trình tích điểm & hạng thành viên, ví khách hàng (nạp/tiêu/hoàn), danh mục sản phẩm bắp nước (Admin) & tồn kho theo chi nhánh, bán bắp nước tại quầy (offline), đặt bắp nước online gắn với vé/suất chiếu và nhận tại quầy, combo sản phẩm, đối soát ca bán hàng (shift/cash reconciliation), thông báo cho khách hàng (xác nhận đặt vé, nhắc suất chiếu, hủy/hoàn).
  - Tất cả các luồng đều có validation đầy đủ: kiểm tra đầu vào, kiểm tra quy tắc nghiệp vụ, kiểm tra chuyển trạng thái hợp lệ, và kiểm tra quyền theo phạm vi chi nhánh.
- **Out of scope**:
  - Tích hợp cổng thanh toán thật (chỉ mô phỏng), phát hành hóa đơn điện tử/kế toán thuế, quản lý chuỗi cung ứng/nguồn nhập hàng F&B từ nhà cung cấp bên ngoài (chỉ kiểm kê tồn kho nội bộ chi nhánh), tích hợp bên thứ ba cho phân phối phim, ứng dụng mobile native, và vận hành hạ tầng triển khai.
- **Adjacent expectations**:
  - Hệ thống dựa vào dịch vụ xác thực phiên đăng nhập (session) và quản lý người dùng do hệ thống này tự cung cấp; không phụ thuộc hệ thống IAM ngoài.
  - Cổng thanh toán online được mô phỏng (mock) — hệ thống tin tưởng callback giả lập trong phạm vi demo/học thuật, không yêu cầu ký số thực.
  - Email/SMS thông báo (nếu có) là best-effort, không chặn luồng đặt vé/hủy vé.

## Requirements

### Requirement 1: Quản lý chi nhánh (Branch Management)

**Objective:** As Admin, I want quản lý danh sách chi nhánh và trạng thái hoạt động, so that toàn chuỗi được vận hành tập trung và dữ liệu chi nhánh nhất quán.

#### Acceptance Criteria
1. When Admin tạo chi nhánh mới với tên, địa chỉ, số điện thoại và trạng thái hoạt động, the System shall kiểm tra các trường bắt buộc, kiểm tra tên chi nhánh không trùng, và lưu chi nhánh ở trạng thái hoạt động.
2. If Admin tạo chi nhánh với tên đã tồn tại (không phân biệt hoa thường, đã chuẩn hóa khoảng trắng), then the System shall từ chối tạo mới và hiển thị thông báo lỗi trùng tên chi nhánh.
3. When Admin cập nhật thông tin chi nhánh, the System shall kiểm tra chi nhánh tồn tại, kiểm tra trùng tên (loại trừ chính chi nhánh đang sửa), và áp dụng thay đổi.
4. If Admin vô hiệu hóa (deactivate) chi nhánh đang có lịch chiếu tương lai ở trạng thái chưa kết thúc, then the System shall từ chối vô hiệu hóa và yêu cầu hủy hoặc dời các lịch chiếu liên quan trước.
5. When người dùng bất kỳ truy vấn danh sách chi nhánh, the System shall chỉ trả về chi nhánh ở trạng thái hoạt động cho vai trò Guest, Customer và Branch Staff, và trả về toàn bộ (kèm trạng thái) cho Admin.
6. While chi nhánh ở trạng thái vô hiệu hóa, the System shall chặn mọi thao tác tạo mới phòng chiếu, lịch chiếu và giao dịch bán vé thuộc chi nhánh đó và thông báo chi nhánh đã ngừng hoạt động.

### Requirement 2: Quản lý danh mục phim (Movie Catalog — Admin tập trung)

**Objective:** As Admin, I want quản lý danh mục phim tập trung cho toàn chuỗi, so that các chi nhánh sử dụng chung một nguồn dữ liệu phim nhất quán và giá vé được áp dụng thống nhất theo khung giá.

#### Acceptance Criteria
1. When Admin tạo mới phim với tiêu đề, thời lượng (phút), thể loại, phân loại độ tuổi, ngày khởi chiếu/kết thúc chiếu, poster và mô tả, the System shall kiểm tra tiêu đề bắt buộc, thời lượng là số nguyên dương, ngày kết thúc chiếu không trước ngày khởi chiếu, và lưu phim ở trạng thái nháp hoặc đã phát hành.
2. If Admin tạo phim với thời lượng không phải số nguyên dương hoặc tiêu đề rỗng, then the System shall từ chối và hiển thị lỗi kiểm tra đầu vào tương ứng.
3. When Admin cập nhật phim đã có lịch chiếu tương lai, the System shall cho phép sửa mô tả/poster/thể loại nhưng chặn sửa thời lượng và phân loại độ tuổi nếu việc sửa làm lịch chiếu hiện có vi phạm quy tắc thời lượng hoặc độ tuổi.
4. If Admin xóa (hoặc vô hiệu hóa) phim đang có lịch chiếu tương lai chưa kết thúc, then the System shall từ chối và liệt kê các lịch chiếu bị ảnh hưởng.
5. When Branch Manager/Branch Staff truy vấn danh mục phim để xếp lịch, the System shall chỉ hiển thị phim ở trạng thái đã phát hành và đang trong khoảng ngày chiếu hiệu lực.
6. When Admin phân loại phim theo độ tuổi (ví dụ: P, C13, C16, C18), the System shall lưu phân loại và áp dụng kiểm tra độ tuổi tại thời điểm đặt vé/soát vé nếu có thông tin tuổi của khách.

### Requirement 3: Quản lý phòng chiếu & sơ đồ ghế (Screens & Seat Maps — branch-scoped)

**Objective:** As Branch Manager, I want quản lý phòng chiếu và sơ đồ ghế của chi nhánh mình, so that mỗi phòng có cấu hình ghế chính xác phục vụ xếp lịch và bán vé.

#### Acceptance Criteria
1. When Branch Manager tạo phòng chiếu thuộc chi nhánh của mình với tên/mã phòng, số hàng, số ghế mỗi hàng và loại ghế theo vị trí, the System shall kiểm tra chi nhánh tồn tại và đang hoạt động, kiểm tra tên/mã phòng không trùng trong cùng chi nhánh, và sinh sơ đồ ghế tương ứng.
2. If Branch Manager tạo phòng chiếu với số hàng hoặc số ghế mỗi hàng không phải số nguyên dương, then the System shall từ chối và báo lỗi kiểm tra đầu vào.
3. When Branch Manager cập nhật sơ đồ ghế của phòng chưa có lịch chiếu tương lai, the System shall cho phép thay đổi số hàng/cột và loại ghế và đồng bộ lại sơ đồ ghế.
4. If Branch Manager cập nhật sơ đồ ghế của phòng đang có lịch chiếu tương lai đã mở bán, then the System shall từ chối thay đổi làm giảm số ghế hoặc đổi loại ghế của ghế đã có vé/giữ chỗ và yêu cầu hủy hoặc dời lịch chiếu trước.
5. When Branch Manager vô hiệu hóa phòng chiếu, the System shall kiểm tra không còn lịch chiếu tương lai chưa kết thúc thuộc phòng đó; nếu còn thì từ chối vô hiệu hóa.
6. If người dùng thuộc chi nhánh A thao tác tạo/sửa phòng chiếu thuộc chi nhánh B, then the System shall từ chối do vi phạm phạm vi chi nhánh và ghi nhận sự kiện vào nhật ký.

### Requirement 4: Quản lý lịch chiếu (Showtime Scheduling & Conflict Validation)

**Objective:** As Branch Manager, I want xếp lịch chiếu cho phòng thuộc chi nhánh mình với kiểm tra xung đột đầy đủ, so that không xảy ra trùng lịch phòng và lịch chiếu luôn hợp lệ.

#### Acceptance Criteria
1. When Branch Manager tạo lịch chiếu với phim, phòng chiếu, thời gian bắt đầu và giá vé (hoặc khung giá), the System shall kiểm tra phòng thuộc chi nhánh của người thao tác, phim đang ở trạng thái phát hành và trong khoảng ngày chiếu, thời gian bắt đầu ở tương lai, và thời gian kết thúc được tính bằng thời gian bắt đầu cộng thời lượng phim cộng thời gian dọn phòng (buffer) cấu hình.
2. If thời gian chiếu mới giao thoa với bất kỳ lịch chiếu đã tồn tại trong cùng phòng (kể cả buffer dọn phòng), then the System shall từ chối tạo lịch chiếu và hiển thị chi tiết lịch chiếu xung đột.
3. If hai yêu cầu tạo lịch chiếu cho cùng phòng với thời gian giao thoa được gửi đồng thời, then the System shall chỉ cho phép một yêu cầu thành công và yêu cầu còn lại nhận lỗi xung đột lịch chiếu, đảm bảo không tạo trùng lịch do race condition.
4. When Branch Manager cập nhật thời gian của lịch chiếu chưa mở bán hoặc chưa có vé, the System shall kiểm tra lại toàn bộ điều kiện như khi tạo mới, bao gồm kiểm tra giao thoa với các lịch chiếu khác (loại trừ chính lịch chiếu đang sửa).
5. If Branch Manager cập nhật hoặc xóa lịch chiếu đã có vé ở trạng thái confirmed hoặc hold, then the System shall từ chối và yêu cầu xử lý vé liên quan (hủy/hoàn tiền/dời lịch) trước.
6. When lịch chiếu đã qua thời gian kết thúc cộng buffer, the System shall tự động chuyển trạng thái lịch chiếu sang đã kết thúc và chặn mọi thao tác đặt vé mới cho lịch chiếu đó.
7. While lịch chiếu ở trạng thái đã kết thúc hoặc đã hủy, the System shall chặn mọi thao tác tạo mới vé/giữ ghế cho lịch chiếu đó.

### Requirement 5: Khung giá & tính giá vé (Pricing Rules — loại ghế × khung giờ)

**Objective:** As Admin, I want định nghĩa khung giá vé theo loại ghế và khung giờ/ngày, so that giá vé được tính tự động, minh bạch và nhất quán toàn chuỗi.

#### Acceptance Criteria
1. When Admin định nghĩa khung giá với loại ghế (thường/VIP/đôi), loại ngày (ngày thường/cuối tuần/ngày lễ) và khung giờ (ví dụ: suất sớm/suất muộn), the System shall kiểm tra không trùng khung giá cho cùng tổ hợp loại ghế + loại ngày + khung giờ và lưu khung giá.
2. If Admin tạo khung giá trùng tổ hợp đã tồn tại, then the System shall từ chối và báo lỗi trùng khung giá.
3. When hệ thống tính giá vé cho một ghế trong một lịch chiếu, the System shall xác định loại ghế của ghế đó, xác định loại ngày và khung giờ từ thời gian bắt đầu lịch chiếu, tra khung giá tương ứng và trả về giá vé; nếu không có khung giá khớp thì áp dụng giá mặc định đã cấu hình.
4. If khung giá bị cập nhật sau khi vé đã được tạo, then the System shall giữ nguyên giá vé đã ghi nhận trên vé, chỉ áp dụng khung giá mới cho các vé tạo sau thời điểm cập nhật.
5. When Branch Manager xem giá vé của lịch chiếu thuộc chi nhánh mình, the System shall hiển thị giá vé đã tính theo khung giá hiện hành và cho phép xem chi tiết cách giá được suy ra.

### Requirement 6: Tìm kiếm & hiển thị suất chiếu cho khách (Showtime Discovery)

**Objective:** As Guest or Customer, I want tìm kiếm và xem suất chiếu theo phim/chi nhánh/ngày, so that tôi chọn được suất chiếu phù hợp để đặt vé.

#### Acceptance Criteria
1. When Guest hoặc Customer truy vấn suất chiếu theo chi nhánh, phim hoặc ngày, the System shall trả về danh sách lịch chiếu ở trạng thái mở bán, thuộc chi nhánh hoạt động, phim đang phát hành, và thời gian bắt đầu ở tương lai, kèm thông tin phòng, thời gian, giá vé theo loại ghế và số ghế còn trống — Guest không cần đăng nhập để xem.
2. If không có suất chiếu thỏa điều kiện lọc, then the System shall trả về danh sách rỗng kèm thông báo không có suất chiếu phù hợp.
3. When Customer xem chi tiết suất chiếu, the System shall hiển thị sơ đồ ghế với trạng thái từng ghế: trống, đang giữ (hold), đã bán (sold), ghế đôi/VIP, và thời gian giữ ghế còn lại nếu đang giữ.
4. When Customer lọc suất chiếu theo chi nhánh, the System shall chỉ trả về suất chiếu thuộc chi nhánh được chọn; nếu chi nhánh đã vô hiệu hóa thì không trả về suất chiếu nào của chi nhánh đó.

### Requirement 7: Đặt vé & giữ ghế — luồng chính (Booking & Seat Hold — 10 phút)

**Objective:** As Customer, I want chọn ghế và đặt vé với cơ chế giữ ghế 10 phút, so that tôi có thời gian hoàn tất thanh toán mà không bị người khác chiếm ghế.

#### Acceptance Criteria
1. When Customer chọn một hoặc nhiều ghế trống cho một lịch chiếu và xác nhận giữ ghế, the System shall kiểm tra lịch chiếu ở trạng thái mở bán, các ghế thuộc phòng của lịch chiếu, ghế đang ở trạng thái trống, và tạo bản ghi giữ ghế (hold) với thời hạn 10 phút tính từ thời điểm tạo, đồng thời chuyển ghế sang trạng thái đang giữ.
2. If Customer chọn ghế đã ở trạng thái đang giữ hoặc đã bán, then the System shall từ chối giữ ghế và thông báo ghế không còn trống, kèm trạng thái hiện tại của ghế.
3. While ghế đang ở trạng thái giữ và chưa hết hạn 10 phút, the System shall chặn mọi yêu cầu giữ ghế khác cho cùng ghế trong cùng lịch chiếu và thông báo ghế đang được giữ.
4. When thời hạn 10 phút hết mà vé chưa được xác nhận thanh toán, the System shall tự động giải phóng ghế (chuyển về trống), hủy bản ghi giữ ghế và cho phép người khác đặt lại ghế đó.
5. When Customer xác nhận đặt vé trong thời hạn giữ ghế và thanh toán được xác nhận, the System shall chuyển ghế sang đã bán, tạo vé ở trạng thái confirmed với giá vé đã ghi nhận và chi tiết ghế/lịch chiếu/chi nhánh.
6. If Customer cố gắng xác nhận đặt vé sau khi thời hạn giữ ghế đã hết, then the System shall từ chối xác nhận, thông báo giữ ghế đã hết hạn và yêu cầu chọn lại ghế.
7. When Customer đặt vé tại quầy (Branch Staff thao tác), the System shall cho phép Branch Staff chọn ghế trống và tạo vé confirmed ngay sau khi xác nhận thanh toán tiền mặt, bỏ qua bước giữ ghế 10 phút nhưng vẫn áp dụng kiểm tra ghế trống và phạm vi chi nhánh.

### Requirement 8: Kiểm soát đồng thời khi đặt vé (Concurrent Booking Control)

**Objective:** As Admin, I want hệ thống xử lý chính xác các yêu cầu đặt vé đồng thời, so that không bao giờ xảy ra đặt ghế đôi (double-booking) dù nhiều người cùng chọn cùng ghế cùng lúc.

#### Acceptance Criteria
1. If hai khách hàng gửi yêu cầu giữ cùng một ghế cho cùng lịch chiếu đồng thời, then the System shall chỉ cho phép một yêu cầu giữ ghế thành công và yêu cầu còn lại nhận lỗi ghế đã được giữ, đảm bảo ghế không bị giữ trùng.
2. If hai yêu cầu xác nhận thanh toán cho cùng ghế (hoặc cùng nhóm ghế có giao nhau) được gửi đồng thời, then the System shall chỉ cho phép một giao dịch chuyển sang confirmed và giao dịch còn lại bị từ chối do ghế không còn trống.
3. When hệ thống xử lý giữ ghế và xác nhận vé, the System shall đảm bảo thao tác kiểm-tra-trạng-thái ghế rồi cập-nhật-trạng-thái diễn ra nguyên tử, sao cho không thể có hai giao dịch cùng vượt qua bước kiểm tra cho cùng một ghế.
4. While một ghế đang được xử lý trong một giao dịch đặt vé (giữ ghế hoặc xác nhận), the System shall đảm bảo giao dịch còn lại quan sát được trạng thái mới nhất sau khi giao dịch đầu tiên hoàn tất, không dựa trên dữ liệu đọc trước đó đã lỗi thời.
5. If hệ thống phát hiện xung đột do nhiều giao dịch cùng cập nhật trạng thái ghế đồng thời, then the System shall từ chối giao dịch xung đột, thông báo ghế vừa được người khác đặt và yêu cầu khách chọn lại.
6. When nhiều khách hàng đồng thời truy vấn số ghế còn trống của cùng lịch chiếu, the System shall trả về số liệu nhất quán dựa trên trạng thái mới nhất đã hoàn tất, không đếm ghế đang giữ đã hết hạn nhưng chưa được giải phóng quá 30 giây.

### Requirement 9: Thanh toán — tiền mặt, cổng mock, chuyển khoản (Payments)

**Objective:** As Customer and Branch Staff, I want thanh toán vé qua các phương thức được hỗ trợ, so that giao dịch được ghi nhận chính xác và vé được xác nhận đúng quy tắc từng phương thức.

#### Acceptance Criteria
1. When Customer chọn thanh toán tiền mặt tại quầy, the System shall tạo vé ở trạng thái pending chờ Branch Staff xác nhận thu tiền, và chỉ chuyển sang confirmed khi Branch Staff thuộc chi nhánh của lịch chiếu xác nhận đã thu tiền.
2. When Customer chọn thanh toán qua cổng online (mock), the System shall tạo phiên thanh toán mock, chuyển hướng (hoặc hiển thị) trang thanh toán giả lập và chờ callback giả lập báo kết quả.
3. If callback mock báo thanh toán thành công trong thời hạn giữ ghế, then the System shall chuyển vé sang confirmed, chuyển ghế sang đã bán và ghi nhận phương thức là cổng online mock.
4. If callback mock báo thanh toán thất bại hoặc không có callback trong thời hạn giữ ghế, then the System shall giữ vé ở trạng thái pending cho đến khi hết hạn giữ ghế, sau đó tự động hủy vé và giải phóng ghế.
5. When Customer chọn chuyển khoản, the System shall tạo vé ở trạng thái pending-chuyển-khoản, hiển thị thông tin chuyển khoản và chờ Branch Staff xác nhận đối soát thủ công.
6. If Branch Staff xác nhận chuyển khoản thành công, then the System shall chuyển vé sang confirmed; nếu Branch Staff từ chối (không nhận được tiền) thì chuyển vé sang cancelled và giải phóng ghế.
7. If hai callback thanh toán (hoặc Branch Staff xác nhận) cho cùng vé được gửi đồng thời, then the System shall chỉ xử lý một lần chuyển trạng thái sang confirmed và lần còn lại nhận lỗi vé đã được xử lý, đảm bảo không ghi nhận trùng thanh toán.
8. While vé ở trạng thái confirmed, the System shall chặn mọi thao tác xác nhận thanh toán bổ sung cho cùng vé.

### Requirement 10: Voucher / mã khuyến mãi (Voucher & Promo Codes)

**Objective:** As Customer, I want áp dụng voucher/mã khuyến mãi khi đặt vé, so that tôi được giảm giá theo đúng điều kiện của chương trình.

#### Acceptance Criteria
1. When Customer nhập mã voucher khi đặt vé, the System shall kiểm tra mã tồn tại, đang trong thời gian hiệu lực, chưa hết lượt sử dụng, áp dụng cho chi nhánh/phim/suất chiếu hiện tại và thỏa điều kiện giá trị đơn hàng tối thiểu nếu có.
2. If voucher đã hết hạn, hết lượt sử dụng, không áp dụng cho chi nhánh/phim hiện tại hoặc đơn hàng không đạt giá trị tối thiểu, then the System shall từ chối áp dụng voucher và hiển thị lý do cụ thể.
3. When voucher được áp dụng thành công, the System shall tính lại tổng tiền sau giảm giá, ghi nhận mã voucher trên vé/đơn hàng và giảm số lượt sử dụng còn lại của voucher đi một.
4. If hai khách hàng áp dụng cùng voucher còn một lượt sử dụng cuối cùng đồng thời, then the System shall chỉ cho phép một giao dịch áp dụng thành công và giao dịch còn lại nhận lỗi voucher đã hết lượt.
5. When vé đã áp dụng voucher bị hủy và được hoàn tiền, the System shall hoàn lại một lượt sử dụng cho voucher đó nếu chính sách voucher cho phép hoàn lượt.

### Requirement 11: Hủy vé & hoàn tiền bậc thang (Cancellation & Tiered Refund)

**Objective:** As Customer and Branch Staff, I want hủy vé và nhận hoàn tiền theo chính sách bậc thang theo thời gian, so that quyền lợi khách hàng được đảm bảo và rạp giảm thiểu thất thoát do hủy sát giờ.

#### Acceptance Criteria
1. When Customer yêu cầu hủy vé đã confirmed, the System shall xác định thời gian còn lại đến giờ bắt đầu lịch chiếu và áp dụng chính sách: cách giờ chiếu ≥ 24 giờ hoàn 100%, từ 2 giờ đến dưới 24 giờ hoàn 50%, dưới 2 giờ không hoàn, và đã soát vé (used) thì không được hủy/hoàn.
2. If Customer yêu cầu hủy vé đã ở trạng thái cancelled hoặc used, then the System shall từ chối và thông báo vé không thể hủy ở trạng thái hiện tại.
3. When Branch Staff hủy vé tại quầy theo yêu cầu khách, the System shall áp dụng cùng chính sách bậc thang, kiểm tra Branch Staff thuộc chi nhánh của vé, và ghi nhận người thực hiện hủy.
4. If vé được thanh toán qua cổng online mock, then the System shall ghi nhận yêu cầu hoàn tiền với số tiền theo bậc thang và chuyển trạng thái vé sang cancelled kèm thông tin hoàn tiền.
5. When vé bị hủy thành công, the System shall giải phóng ghế liên quan (chuyển về trống), cập nhật lịch chiếu và cho phép ghế được đặt lại ngay lập tức.
6. If hai yêu cầu hủy cùng một vé được gửi đồng thời, then the System shall chỉ xử lý một yêu cầu thành công và yêu cầu còn lại nhận lỗi vé đã được hủy.
7. While vé đã được soát (trạng thái used), the System shall chặn mọi thao tác hủy và hoàn tiền cho vé đó.

### Requirement 12: Soát vé tại cửa phòng chiếu (Ticket Validation at Door)

**Objective:** As Branch Staff, I want soát vé tại cửa phòng chiếu trước giờ chiếu, so that chỉ vé hợp lệ được vào phòng và mỗi vé chỉ được sử dụng một lần.

#### Acceptance Criteria
1. When Branch Staff quét hoặc nhập mã vé tại cửa phòng chiếu, the System shall kiểm tra vé tồn tại, thuộc đúng chi nhánh và phòng chiếu của lịch chiếu, ở trạng thái confirmed và lịch chiếu chưa kết thúc.
2. If vé ở trạng thái pending, cancelled hoặc used, then the System shall từ chối cho vào và hiển thị lý do vé không hợp lệ (chưa thanh toán/đã hủy/đã sử dụng).
3. When vé hợp lệ được soát thành công, the System shall chuyển vé sang trạng thái used, ghi nhận thời gian soát vé và người thực hiện, và chặn mọi lần soát lại sau đó.
4. If hai nhân viên soát cùng một vé đồng thời, then the System shall chỉ cho phép một lần soát thành công và lần còn lại nhận lỗi vé đã được sử dụng.
5. If vé thuộc chi nhánh A nhưng được soát tại chi nhánh B, then the System shall từ chối soát vé do không khớp chi nhánh.
6. When lịch chiếu đã qua thời gian kết thúc, the System shall từ chối soát vé cho lịch chiếu đó và thông báo suất chiếu đã kết thúc.

### Requirement 13: Phân quyền & phạm vi chi nhánh (Branch-Scoped RBAC)

**Objective:** As Admin, I want phân quyền theo vai trò và phạm vi chi nhánh, so that mỗi người dùng chỉ thao tác trong phạm vi được phép và dữ liệu chi nhánh được cô lập.

#### Acceptance Criteria
1. When hệ thống kiểm tra quyền truy cập, the System shall xác định vai trò của người dùng (Admin, Branch Manager, Branch Staff, Customer, Guest) và phạm vi chi nhánh được gán, từ chối mọi thao tác vượt phạm vi.
2. If người dùng chưa đăng nhập truy cập tài nguyên yêu cầu xác thực, then the System shall chuyển hướng đến trang đăng nhập hoặc trả về lỗi chưa xác thực.
3. If người dùng đã đăng nhập nhưng không có quyền cho thao tác yêu cầu (ví dụ: Branch Staff cố gắng quản lý phim (Admin), Customer cố gắng truy cập báo cáo), then the System shall từ chối với lỗi không đủ quyền.
4. When Admin gán hoặc thay đổi phạm vi chi nhánh cho một tài khoản Branch Staff, the System shall cập nhật phạm vi ngay lập tức và các yêu cầu tiếp theo của tài khoản đó phải tuân theo phạm vi mới.
5. While người dùng thuộc chi nhánh A, the System shall đảm bảo mọi truy vấn danh sách (phòng chiếu, lịch chiếu, vé, báo cáo) chỉ trả về dữ liệu thuộc chi nhánh A, và mọi thao tác ghi chỉ tác động dữ liệu thuộc chi nhánh A.
6. When Admin truy vấn hoặc thao tác, the System shall cho phép truy cập dữ liệu toàn chuỗi nhưng vẫn ghi nhận chi nhánh liên quan trên mỗi bản ghi để phục vụ truy vết.

### Requirement 14: Báo cáo & thống kê (Reporting & Analytics)

**Objective:** As Admin and Branch Manager, I want xem báo cáo doanh thu và vận hành theo chi nhánh/phim/khoảng thời gian, so that tôi đánh giá hiệu quả kinh doanh và ra quyết định.

#### Acceptance Criteria
1. When Admin truy vấn báo cáo doanh thu theo khoảng thời gian, chi nhánh hoặc phim, the System shall trả về tổng doanh thu từ vé confirmed/used, số vé bán, số vé hủy và số tiền hoàn, chỉ tính vé thuộc khoảng thời gian và phạm vi được chọn.
2. When Branch Manager truy vấn báo cáo, the System shall chỉ trả về dữ liệu thuộc chi nhánh của họ, từ chối truy vấn dữ liệu chi nhánh khác.
3. If khoảng thời gian truy vấn không hợp lệ (ngày kết thúc trước ngày bắt đầu), then the System shall từ chối và báo lỗi kiểm tra đầu vào.
4. When báo cáo được yêu cầu trong khi có giao dịch đặt vé/hủy vé đang diễn ra đồng thời, the System shall đảm bảo số liệu báo cáo phản ánh trạng thái đã hoàn tất tại thời điểm truy vấn, không đếm trùng hoặc bỏ sót do đọc dữ liệu đang thay đổi.
5. When người dùng xuất báo cáo, the System shall hiển thị chi tiết theo ngày/phim/suất chiếu tùy bộ lọc và cho phép phân trang khi dữ liệu lớn.

### Requirement 15: Kiểm tra dữ liệu, xử lý lỗi & nhật ký thao tác (Validation, Error Handling & Audit Log)

**Objective:** As Admin, I want mọi thao tác đều được kiểm tra dữ liệu, xử lý lỗi nhất quán và ghi nhật ký, so that hệ thống vận hành tin cậy và có thể truy vết khi có sự cố.

#### Acceptance Criteria
1. When người dùng gửi dữ liệu đầu vào (biểu mẫu, tham số URL, JSON), the System shall kiểm tra kiểu dữ liệu, độ dài, định dạng và ràng buộc bắt buộc trước khi xử lý nghiệp vụ và trả về lỗi chi tiết nếu kiểm tra thất bại.
2. If thao tác vi phạm quy tắc nghiệp vụ (trùng lịch, ghế không trống, voucher không hợp lệ, hủy ngoài chính sách), then the System shall từ chối thao tác, trả về mã lỗi và thông báo rõ ràng cho người dùng, và không thay đổi dữ liệu liên quan.
3. When thao tác thay đổi trạng thái (tạo/sửa/xóa lịch chiếu, đặt vé, thanh toán, hủy/hoàn, soát vé, quản lý voucher), the System shall ghi nhật ký thao tác bao gồm người thực hiện, thời gian, loại thao tác, dữ liệu trước/sau (khi phù hợp) và kết quả.
4. If hệ thống gặp lỗi không mong muốn (ngoại lệ hệ thống), then the System shall ghi log lỗi chi tiết ở phía máy chủ, trả về thông báo lỗi chung cho người dùng mà không lộ chi tiết kỹ thuật, và đảm bảo giao dịch được rollback để không để lại dữ liệu nửa vời.
5. While người dùng thao tác với dữ liệu đã lỗi thời do đồng thời (ví dụ: sửa lịch chiếu đã bị người khác sửa), the System shall phát hiện xung đột phiên bản và yêu cầu người dùng tải lại dữ liệu mới nhất trước khi thử lại.


### Requirement 16: Tài khoản khách hàng & lịch sử mua (Customer Account & Purchase History)

**Objective:** As Customer, I want đăng ký tài khoản và xem lịch sử mua vé/bắp nước của mình, so that tôi quản lý được giao dịch, tích điểm và đặt vé nhanh hơn cho lần sau.

#### Acceptance Criteria
1. When khách hàng đăng ký tài khoản với họ tên, email, số điện thoại và mật khẩu, the System shall kiểm tra định dạng email/số điện thoại hợp lệ, mật khẩu đạt độ mạnh tối thiểu (độ dài, chứa chữ và số), email/số điện thoại chưa được đăng ký, và tạo tài khoản ở trạng thái hoạt động.
2. If khách hàng đăng ký với email hoặc số điện thoại đã tồn tại, then the System shall từ chối đăng ký và thông báo tài khoản đã tồn tại kèm gợi ý đăng nhập.
3. When khách hàng đăng nhập với thông tin hợp lệ, the System shall tạo phiên đăng nhập có thời hạn, ghi nhận thời điểm đăng nhập cuối và giới hạn số lần đăng nhập sai liên tiếp (khóa tạm thời sau 5 lần sai trong 15 phút).
4. If khách hàng nhập sai mật khẩu, then the System shall thông báo lỗi đăng nhập chung (không tiết lộ email có tồn tại hay không) và tăng bộ đếm lần sai.
5. When khách hàng đã đăng nhập truy cập lịch sử mua, the System shall trả về danh sách vé và đơn bắp nước của chính tài khoản đó theo thời gian giảm dần, kèm trạng thái (pending/confirmed/cancelled/used) và điểm tích lũy của từng giao dịch.
6. If khách hàng truy cập lịch sử mua của tài khoản khác (thay đổi định danh trên yêu cầu), then the System shall từ chối và ghi nhận sự kiện truy cập trái phép vào nhật ký.
7. When khách hàng cập nhật thông tin cá nhân (họ tên, số điện thoại), the System shall kiểm tra định dạng đầu vào, kiểm tra số điện thoại mới không trùng tài khoản khác, và áp dụng thay đổi.

### Requirement 17: Tích điểm & hạng thành viên (Loyalty Points & Membership Tiers)

**Objective:** As Customer, I want tích điểm khi mua vé/bắp nước và lên hạng thành viên, so that tôi được hưởng ưu đãi theo mức độ gắn bó; As Admin, I want cấu hình chương trình tích điểm tập trung cho toàn chuỗi.

#### Acceptance Criteria
1. When Admin cấu hình chương trình tích điểm (tỷ lệ điểm trên mỗi đơn vị tiền chi tiêu, ngưỡng lên hạng, quyền lợi từng hạng), the System shall kiểm tra tỷ lệ là số dương, các ngưỡng hạng tăng dần và không trùng tên hạng, rồi áp dụng cấu hình cho toàn chuỗi.
2. When giao dịch vé hoặc bắp nước chuyển sang trạng thái confirmed và đã thanh toán, the System shall cộng điểm tích lũy cho tài khoản khách hàng theo tỷ lệ hiện hành tính trên tổng tiền thực trả (sau giảm giá voucher) và ghi nhận giao dịch điểm.
3. When tổng điểm tích lũy lũy kế của khách hàng vượt ngưỡng hạng cao hơn, the System shall tự động nâng hạng thành viên và thông báo khách hàng về hạng mới cùng quyền lợi.
4. If vé/đơn bắp nước đã cộng điểm bị hủy và hoàn tiền, then the System shall trừ lại số điểm đã cộng của giao dịch đó; nếu số dư điểm không đủ trừ (khách đã tiêu điểm), then the System shall ghi nhận số dư điểm âm theo chính sách do Admin cấu hình và hiển thị cảnh báo cho khách hàng.
5. When khách hàng xem trang tài khoản, the System shall hiển thị số dư điểm hiện tại, hạng thành viên, điểm cần thêm để lên hạng kế tiếp và lịch sử giao dịch điểm (cộng/trừ, nguồn giao dịch, thời gian).
6. If hai giao dịch cộng điểm cho cùng một tài khoản diễn ra đồng thời, then the System shall đảm bảo số dư điểm cuối cùng chính xác bằng tổng của cả hai giao dịch, không mất cập nhật do race condition.
7. While khách hàng ở hạng thành viên có quyền lợi giảm giá, the System shall tự động áp dụng quyền lợi giảm giá hạng khi tính giá vé/đơn bắp nước trước khi áp voucher (nếu cả hai được phép kết hợp theo cấu hình Admin).
8. When Admin điều chỉnh tỷ lệ tích điểm hoặc ngưỡng hạng, the System shall chỉ áp dụng cấu hình mới cho các giao dịch phát sinh sau thời điểm điều chỉnh, giữ nguyên điểm đã ghi nhận trước đó.

### Requirement 18: Ví khách hàng — nạp tiền, thanh toán, hoàn tiền (Customer Wallet)

**Objective:** As Customer, I want nạp tiền vào ví và dùng số dư ví thanh toán vé/bắp nước, so that giao dịch nhanh hơn và tiền hoàn được trả về ví tức thì.

#### Acceptance Criteria
1. When khách hàng yêu cầu nạp tiền vào ví với số tiền là bội số của 10.000đ và không vượt hạn mức nạp tối đa mỗi lần, the System shall kiểm tra số tiền hợp lệ, tạo giao dịch nạp ở trạng thái pending và chờ xác nhận qua phương thức thanh toán đã chọn (mock).
2. If giao dịch nạp được xác nhận thành công, then the System shall cộng số tiền vào số dư ví và ghi nhận giao dịch nạp; if giao dịch nạp thất bại hoặc hết hạn chờ, then the System shall giữ số dư không đổi và đánh dấu giao dịch nạp thất bại.
3. When khách hàng chọn thanh toán bằng số dư ví cho đơn vé hoặc bắp nước, the System shall kiểm tra số dư đủ cho tổng tiền sau giảm giá, trừ số dư và ghi nhận giao dịch tiêu ví trong cùng một thao tác nguyên tử với việc xác nhận đơn hàng.
4. If số dư ví không đủ để thanh toán, then the System shall từ chối thanh toán bằng ví, thông báo số dư hiện tại và gợi ý nạp thêm hoặc chọn phương thức khác.
5. When vé/đơn bắp nước thanh toán bằng ví bị hủy và hoàn tiền, the System shall hoàn số tiền theo chính sách bậc thang vào số dư ví của khách ngay khi hủy thành công và ghi nhận giao dịch hoàn ví.
6. If hai yêu cầu tiêu ví từ cùng tài khoản diễn ra đồng thời với số dư chỉ đủ cho một, then the System shall chỉ cho phép một yêu cầu thành công và yêu cầu còn lại nhận lỗi số dư không đủ, đảm bảo số dư không bao giờ âm.
7. When khách hàng xem ví, the System shall hiển thị số dư hiện tại và toàn bộ lịch sử giao dịch ví (nạp/tiêu/hoàn) kèm số dư sau mỗi giao dịch, nguồn giao dịch và thời gian.
8. While tài khoản khách hàng ở trạng thái bị khóa, the System shall chặn mọi giao dịch nạp/tiêu ví của tài khoản đó và thông báo liên hệ bộ phận hỗ trợ.

### Requirement 19: Danh mục sản phẩm bắp nước & tồn kho chi nhánh (Concession Catalog & Branch Inventory)

**Objective:** As Admin, I want quản lý danh mục sản phẩm bắp nước tập trung; As Branch Manager, I want quản lý tồn kho sản phẩm tại chi nhánh mình, so that quầy bán hàng luôn có hàng và hệ thống không bán vượt tồn kho.

#### Acceptance Criteria
1. When Admin tạo sản phẩm bắp nước với tên, mô tả, giá bán, đơn vị tính, loại (bắp/nước/combo) và trạng thái kinh doanh, the System shall kiểm tra tên bắt buộc và không trùng, giá bán là số dương, và lưu sản phẩm áp dụng cho toàn chuỗi.
2. When Admin tạo combo gồm nhiều sản phẩm thành phần với số lượng từng thành phần, the System shall kiểm tra các sản phẩm thành phần tồn tại và đang kinh doanh, số lượng nguyên dương, và lưu combo kèm cấu trúc thành phần.
3. If Admin vô hiệu hóa sản phẩm đang nằm trong combo còn hiệu lực, then the System shall từ chối hoặc yêu cầu cập nhật combo liên quan trước, và liệt kê các combo bị ảnh hưởng.
4. When Branch Manager nhập kho sản phẩm tại chi nhánh (số lượng, thời điểm), the System shall kiểm tra sản phẩm thuộc danh mục do Admin quản lý, số lượng nguyên dương, chi nhánh đang hoạt động, và tăng tồn kho chi nhánh tương ứng kèm bản ghi nhập kho.
5. When Branch Manager kiểm kê và điều chỉnh tồn kho, the System shall yêu cầu lý do điều chỉnh (hư hỏng, sai lệch kiểm đếm, hủy hàng), ghi nhận chênh lệch trước/sau và lưu bản ghi kiểm kê phục vụ đối soát.
6. While tồn kho sản phẩm tại chi nhánh bằng 0, the System shall đánh dấu sản phẩm hết hàng tại chi nhánh đó và chặn bán sản phẩm đó (tại quầy và online) cho đến khi nhập kho lại.
7. If Branch Manager truy vấn hoặc điều chỉnh tồn kho của chi nhánh khác, then the System shall từ chối do vi phạm phạm vi chi nhánh.
8. When Admin cập nhật giá bán sản phẩm, the System shall áp dụng giá mới cho các đơn hàng tạo sau thời điểm cập nhật và giữ nguyên giá đã ghi nhận trên đơn hàng trước đó.

### Requirement 20: Bán bắp nước tại quầy (Offline Concession Sales)

**Objective:** As Branch Staff, I want bán bắp nước tại quầy cho khách vãng lai và khách đã có vé, so that doanh thu F&B được ghi nhận cùng hệ thống với vé và trừ tồn kho chính xác.

#### Acceptance Criteria
1. When Branch Staff tạo đơn bắp nước tại quầy cho khách (chọn sản phẩm, số lượng, phương thức thanh toán), the System shall kiểm tra sản phẩm đang kinh doanh tại chi nhánh, tồn kho đủ cho số lượng, giá bán hiện hành, và tạo đơn ở trạng thái pending chờ thanh toán.
2. If tồn kho không đủ cho số lượng khách chọn, then the System shall thông báo số lượng tối đa còn lại và từ chối tạo đơn vượt tồn kho.
3. When Branch Staff xác nhận thanh toán đơn bắp nước (tiền mặt hoặc ví khách hàng khi khách cung cấp tài khoản), the System shall chuyển đơn sang confirmed, trừ tồn kho chi nhánh, cộng điểm tích lũy nếu đơn gắn với tài khoản khách hàng, và hiển thị biên nhận.
4. If hai Branch Staff tạo đơn bán cùng sản phẩm với số lượng vượt quá tồn kho còn lại đồng thời, then the System shall chỉ cho phép các đơn trong giới hạn tồn kho thành công, đơn còn lại nhận lỗi hết hàng.
5. When Branch Staff hủy đơn bắp nước đã confirmed (khách trả hàng), the System shall yêu cầu lý do hủy, kiểm tra quyền Branch Manager trở lên cho thao tác hoàn tiền, hoàn tiền theo phương thức đã thanh toán (tiền mặt/ví), hoàn tồn kho và trừ lại điểm đã cộng nếu có.
6. While đơn bắp nước ở trạng thái pending quá thời gian chờ thanh toán cấu hình (mặc định 30 phút), the System shall tự động hủy đơn và giải phóng tồn kho đã giữ.
7. When Branch Staff bán bắp nước kèm vé (khách xuất trình vé hợp lệ), the System shall cho phép gắn đơn bắp nước với vé để phục vụ thống kê doanh thu theo suất chiếu, kiểm tra vé thuộc chi nhánh và suất chiếu chưa kết thúc.

### Requirement 21: Đặt bắp nước online gắn vé/suất chiếu (Online Concession Pre-order)

**Objective:** As Customer, I want đặt trước bắp nước online cùng lúc đặt vé hoặc cho suất chiếu đã mua vé, so that tôi nhận hàng nhanh tại quầy mà không phải xếp hàng gọi món.

#### Acceptance Criteria
1. When Customer thêm sản phẩm bắp nước vào đơn đặt vé online, the System shall kiểm tra sản phẩm đang kinh doanh tại chi nhánh của suất chiếu, tồn kho chi nhánh đủ tại thời điểm đặt, và gộp giá trị đơn bắp nước vào tổng thanh toán của đơn vé.
2. When Customer đã có vé confirmed muốn đặt thêm bắp nước cho suất chiếu đó, the System shall cho phép tạo đơn bắp nước online gắn với vé, kiểm tra suất chiếu chưa kết thúc và đơn được đặt trước giờ chiếu tối thiểu khoảng thời gian cấu hình (mặc định 30 phút).
3. If đơn bắp nước online được thanh toán thành công, then the System shall chuyển đơn sang trạng thái chờ nhận (ready-for-pickup), trừ tồn kho chi nhánh, cộng điểm tích lũy và tạo mã nhận hàng hiển thị cho khách.
4. When Customer xuất trình mã nhận hàng tại quầy bắp nước của đúng chi nhánh, the System shall kiểm tra mã hợp lệ, đơn ở trạng thái chờ nhận và chưa quá hạn nhận (kết thúc suất chiếu gắn với đơn), rồi chuyển đơn sang đã nhận (fulfilled) và ghi nhận người giao.
5. If mã nhận hàng đã được sử dụng (đơn đã fulfilled), then the System shall từ chối giao hàng lần hai và thông báo đơn đã hoàn tất nhận.
6. If vé gắn với đơn bắp nước online bị hủy, then the System shall tự động hủy đơn bắp nước chưa nhận, hoàn tiền đơn bắp nước theo phương thức đã thanh toán (ưu tiên về ví nếu khách có ví) và hoàn tồn kho.
7. While đơn bắp nước online ở trạng thái pending chưa thanh toán quá thời hạn giữ đơn, the System shall tự động hủy đơn và giải phóng tồn kho đã giữ.
8. If hai Customer đặt sản phẩm cuối cùng trong tồn kho chi nhánh đồng thời qua kênh online, then the System shall chỉ cho phép một đơn thành công và đơn còn lại nhận lỗi hết hàng.
9. When đơn bắp nước online quá hạn nhận mà khách không đến nhận, the System shall chuyển đơn sang trạng thái quá hạn (expired-no-show), không hoàn tiền theo chính sách mặc định và ghi nhận vào báo cáo vận hành.

### Requirement 22: Đối soát ca bán hàng (Shift & Cash Reconciliation)

**Objective:** As Branch Manager, I want đối soát ca bán hàng của nhân viên quầy vé/quầy bắp nước, so that tiền mặt thực tế khớp với giao dịch hệ thống và sai lệch được phát hiện kịp thời.

#### Acceptance Criteria
1. When Branch Staff bắt đầu ca làm việc tại chi nhánh, the System shall mở ca với số dư tiền mặt đầu ca do Branch Staff khai báo, ghi nhận thời điểm mở ca và người mở.
2. While ca đang mở, the System shall gán mọi giao dịch tiền mặt tại quầy (vé, bắp nước, hoàn tiền) của Branch Staff đó vào ca tương ứng để tổng hợp.
3. When Branch Staff kết thúc ca, the System shall tổng hợp theo ca: số giao dịch, doanh thu theo phương thức (tiền mặt/ví/cổng mock/chuyển khoản), số tiền hoàn, và yêu cầu Branch Staff nhập số tiền mặt thực kiểm.
4. If số tiền mặt thực kiểm lệch so với số liệu hệ thống vượt ngưỡng dung sai cấu hình, then the System shall đánh dấu ca có sai lệch, yêu cầu ghi chú giải trình và chuyển ca sang trạng thái chờ Branch Manager phê duyệt.
5. When Branch Manager xem ca có sai lệch, the System shall cho phép phê duyệt kèm ghi nhận xử lý hoặc trả lại ca yêu cầu giải trình bổ sung, và lưu toàn bộ lịch sử phê duyệt.
6. If Branch Staff cố gắng mở ca thứ hai chồng lấn ca đang mở của chính mình, then the System shall từ chối và yêu cầu kết thúc ca hiện tại trước.
7. When Admin xem báo cáo đối soát toàn chuỗi, the System shall tổng hợp số ca, tổng sai lệch và các ca chờ phê duyệt theo từng chi nhánh trong khoảng thời gian được chọn.

### Requirement 23: Thông báo khách hàng (Customer Notifications)

**Objective:** As Customer, I want nhận thông báo về trạng thái đơn vé/bắp nước và nhắc suất chiếu, so that tôi không bỏ lỡ giao dịch quan trọng hoặc suất chiếu đã đặt.

#### Acceptance Criteria
1. When vé của Customer chuyển sang trạng thái confirmed, the System shall gửi thông báo trong hệ thống kèm mã vé, thông tin suất chiếu, ghế và tổng tiền (email ở chế độ best-effort nếu được cấu hình).
2. When vé/đơn bắp nước bị hủy hoặc hoàn tiền, the System shall gửi thông báo kèm lý do và số tiền hoàn (nếu có).
3. When suất chiếu của vé confirmed sắp bắt đầu trong khoảng thời gian nhắc cấu hình (mặc định 60 phút), the System shall gửi thông báo nhắc suất chiếu cho khách hàng sở hữu vé.
4. If kênh email không gửi được (dịch vụ lỗi), then the System shall vẫn hoàn tất luồng nghiệp vụ chính, ghi nhận lỗi gửi thông báo vào nhật ký và không chặn hoặc hoàn tác giao dịch đã thành công.
5. When Customer xem danh sách thông báo, the System shall chỉ trả về thông báo của chính tài khoản đó, phân trạng thái đã đọc/chưa đọc và cho phép đánh dấu đã đọc.
6. While tài khoản Customer bị khóa, the System shall ngừng gửi thông báo tiếp thị nhưng vẫn gửi thông báo giao dịch liên quan đến hoàn tiền đang chờ xử lý.
