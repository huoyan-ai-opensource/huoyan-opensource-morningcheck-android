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
        boolean numberMissing = person.isNull("staff_no") || person.optString("staff_no").trim().isEmpty();
        number.setText((inlineTitles ? number.getContext().getString(R.string.i18n_b_staff_number_prefix) : "")
                + (numberMissing ? number.getContext().getString(R.string.i18n_b_none) : staffNumber));
        number.setTextColor(numberMissing ? Ui.MUTED : Ui.INK);
        boolean active = StaffPresentation.enabled(person);
        enabled.setText((inlineTitles ? number.getContext().getString(R.string.i18n_b_staff_status_prefix) : "") + (active ? number.getContext().getString(R.string.i18n_b_active) : number.getContext().getString(R.string.i18n_b_inactive)));
        enabled.setTextColor(active ? Ui.GREEN : Ui.ORANGE);
        String group = StaffPresentation.department(person);
        boolean groupMissing = person.isNull("department") || person.optString("department").trim().isEmpty();
        department.setText((inlineTitles ? number.getContext().getString(R.string.i18n_b_department_prefix) : "")
                + (groupMissing ? UiMessages.text(number.getContext(), group) : group));
        department.setTextColor(groupMissing ? Ui.MUTED : Ui.BLUE);
        StaffPresentation.Health certificate = StaffPresentation.health(person, LocalDate.now());
        health.setText((inlineTitles ? number.getContext().getString(R.string.i18n_b_health_prefix) : "")
                + UiMessages.text(number.getContext(), certificate.state)
                + (certificate.status == StaffPresentation.HealthStatus.UNASSIGNED ? "" : " · " + certificate.expiry));
        health.setTextColor(certificate.status == StaffPresentation.HealthStatus.VALID ? Ui.GREEN
                : certificate.status == StaffPresentation.HealthStatus.EXPIRED ? Ui.ORANGE : Ui.MUTED);
    }
}
