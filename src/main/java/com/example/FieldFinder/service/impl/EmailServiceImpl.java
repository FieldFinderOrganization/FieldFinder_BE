package com.example.FieldFinder.service.impl;
import com.example.FieldFinder.entity.Order;
import com.example.FieldFinder.entity.OrderItem;
import com.example.FieldFinder.service.EmailService;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.text.NumberFormat;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

@Service
public class    EmailServiceImpl implements EmailService {

    @Autowired
    private final JavaMailSender mailSender;

    public EmailServiceImpl(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    @Override
    public void send(String to, String subject, String body) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);
        mailSender.send(message);
    }

    @Override
    @Async
    public void sendOrderConfirmation(Order order) {
        if (order.getUser() == null || order.getUser().getEmail() == null) {
            System.err.println("Cannot send email: User email is missing for Order #" + order.getOrderId());
            return;
        }

        String to = order.getUser().getEmail();
        String subject = "Xác nhận đơn hàng #" + order.getOrderId() + " - Thanh toán thành công";
        String content = buildOrderHtml(order);

        try {
            sendHtmlEmail(to, subject, content);
            System.out.println("📧 Email sent successfully to " + to);
        } catch (MessagingException e) {
            System.err.println("❌ Failed to send email: " + e.getMessage());
        }
    }

    private void sendHtmlEmail(String to, String subject, String htmlBody) throws MessagingException {
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

        helper.setTo(to);
        helper.setSubject(subject);
        helper.setText(htmlBody, true);

        mailSender.send(message);
    }

    private String buildOrderHtml(Order order) {
        NumberFormat currencyFormatter = NumberFormat.getCurrencyInstance(new Locale("vi", "VN"));
        DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

        StringBuilder html = new StringBuilder();
        html.append("<html><body style='font-family: Arial, sans-serif; color: #333;'>");

        html.append("<div style='background-color: #4CAF50; color: white; padding: 20px; text-align: center;'>");
        html.append("<h1>Cảm ơn bạn đã đặt hàng!</h1>");
        html.append("<p>Đơn hàng #" + order.getOrderId() + " đã được thanh toán thành công.</p>");
        html.append("</div>");

        html.append("<div style='padding: 20px;'>");
        html.append("<p><strong>Khách hàng:</strong> " + order.getUser().getName() + "</p>");
        html.append("<p><strong>Ngày đặt:</strong> " + order.getCreatedAt().format(dateFormatter) + "</p>");
        html.append("<p><strong>Phương thức thanh toán:</strong> " + order.getPaymentMethod() + "</p>");

        // Table Items
        html.append("<table style='width: 100%; border-collapse: collapse; margin-top: 20px;'>");
        html.append("<tr style='background-color: #f2f2f2;'>");
        html.append("<th style='padding: 12px; border: 1px solid #ddd; text-align: left;'>Sản phẩm</th>");
        html.append("<th style='padding: 12px; border: 1px solid #ddd; text-align: center;'>Size</th>");
        html.append("<th style='padding: 12px; border: 1px solid #ddd; text-align: center;'>SL</th>");
        html.append("<th style='padding: 12px; border: 1px solid #ddd; text-align: right;'>Thành tiền</th>");
        html.append("</tr>");

        if (order.getItems() != null) {
            for (OrderItem item : order.getItems()) {
                html.append("<tr>");
                html.append("<td style='padding: 12px; border: 1px solid #ddd;'>" + item.getProduct().getName() + "</td>");
                html.append("<td style='padding: 12px; border: 1px solid #ddd; text-align: center;'>" + item.getSize() + "</td>");
                html.append("<td style='padding: 12px; border: 1px solid #ddd; text-align: center;'>" + item.getQuantity() + "</td>");
                html.append("<td style='padding: 12px; border: 1px solid #ddd; text-align: right;'>" + currencyFormatter.format(item.getPrice()) + "</td>");
                html.append("</tr>");
            }
        }

        // Total
        html.append("<tr>");
        html.append("<td colspan='3' style='padding: 12px; border: 1px solid #ddd; text-align: right;'><strong>Tổng cộng:</strong></td>");
        html.append("<td style='padding: 12px; border: 1px solid #ddd; text-align: right; color: #d32f2f; font-weight: bold;'>" + currencyFormatter.format(order.getTotalAmount()) + "</td>");
        html.append("</tr>");
        html.append("</table>");

        html.append("<p style='margin-top: 20px;'>Mọi thắc mắc xin vui lòng liên hệ bộ phận hỗ trợ.</p>");
        html.append("<p>Trân trọng,<br/>FieldFinder Team</p>");
        html.append("</div>");
        html.append("</body></html>");

        return html.toString();
    }
}
