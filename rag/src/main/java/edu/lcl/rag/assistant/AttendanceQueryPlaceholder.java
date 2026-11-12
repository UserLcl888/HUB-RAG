package edu.lcl.rag.assistant;

import edu.lcl.rag.entity.ChatMessage;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** 仅用于能力登记与意图匹配；尚未提供接口，不读取考勤库、不返回假名单或人数。 */
@Component
public class AttendanceQueryPlaceholder implements BusinessQueryTool {

    @Override
    public Definition definition() {
        return new Definition("attendance_query", "考勤数据查询",
                "查询实际考勤记录、缺勤或未打卡人员、出勤人数等业务数据。"
                        + "不用于解释打卡时间、考勤制度或政策条文。尚未接入；先确认项目，其他参数和口径待真实接口确认。",
                List.of("今天谁没打卡", "查询某项目实际考勤人数"),
                List.of(new Parameter("projectName", "用户明确提供的具体项目名称，不接受“这个项目”“某项目”等未解析指代",
                        true, "你想查询哪个项目的考勤数据（例如缺勤人员或出勤人数）？")), false);
    }

    @Override
    public Result query(String question, Map<String, String> arguments, List<ChatMessage> history) {
        return new Result(State.NOT_CONNECTED, "考勤数据查询暂未接入，目前无法查询实际记录、名单或人数。");
    }
}
