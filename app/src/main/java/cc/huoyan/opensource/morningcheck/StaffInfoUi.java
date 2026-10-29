package cc.huoyan.opensource.morningcheck;

import android.widget.TextView;

import org.json.JSONObject;

import java.time.LocalDate;

import cc.huoyan.opensource.morningcheck.data.StaffPresentation;

/**
 * 员工列表与详情使用相同状态文案，每次绑定都重设颜色，避免列表复用旧状态。
 */
final class StaffInfoUi {
    private StaffInfoUi() {
    }

    static void bind(JSONObject person, TextView number, TextView enabled, TextView department, TextView health) {
        bind(person, number, enabled, department, health, true);
    }

    static void bindValues(JSONObject person, TextView number, TextView enabled, TextView department, TextView health) {
        bind(person, number, enabled, department, health, false);
    }

    private static void bind(JSONObject person, TextView number, TextView enabled, TextView department,
                             TextView health, boolean inlineTitles) {
        if (person == null) {
            for (TextView field : new TextView[]{number, enabled, department, health}) {
                field.setText("");
                field.setTextColor(Ui.MUTED);
            }
            return;
        }
        String staffNumber = StaffPresentation.number(person);
        number.setText((inlineTitles ? "工号：" : "") + staffNumber);
        number.setTextColor("无".equals(staffNumber) ? Ui.MUTED : Ui.INK);
        boolean active = StaffPresentation.enabled(person);
        enabled.setText((inlineTitles ? "启用状态：" : "") + (active ? "启用中" : "已停用"));
        enabled.setTextColor(active ? Ui.GREEN : Ui.ORANGE);
        String group = StaffPresentation.department(person);
        department.setText((inlineTitles ? "部门：" : "") + group);
        department.setTextColor("未分配".equals(group) ? Ui.MUTED : Ui.BLUE);
        StaffPresentation.Health certificate = StaffPresentation.health(person, LocalDate.now());
        health.setText((inlineTitles ? "健康证：" : "") + certificate.state
                + (certificate.status == StaffPresentation.HealthStatus.UNASSIGNED ? "" : " · " + certificate.expiry));
        health.setTextColor(certificate.status == StaffPresentation.HealthStatus.VALID ? Ui.GREEN
                : certificate.status == StaffPresentation.HealthStatus.EXPIRED ? Ui.ORANGE : Ui.MUTED);
    }
}
