package com.example.be.domain.feedback.repository;

import com.example.be.domain.feedback.exception.FeedbackErrors;
import com.example.be.domain.feedback.util.FeedbackTokens;
import com.example.be.global.database.OracleInClause;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.io.StringReader;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

import static com.example.be.domain.feedback.model.FeedbackModels.*;

@Repository
@RequiredArgsConstructor
public class FeedbackStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public void capability(String hash, long recipientId, Snapshot snapshot, LocalDateTime now) {
        insert("INSERT INTO news_feedback_capabilities(token_hash,report_id,recipient_id,snapshot_json,created_at,expires_at) VALUES(?,?,?,?,?,?)",
                hash, snapshot.reportId(), recipientId, clob(snapshot), now, now.plusDays(30));
    }
    public Optional<Capability> capability(String hash, LocalDateTime now) {
        return jdbc.query("""
                SELECT c.* FROM news_feedback_capabilities c JOIN notification_recipients r ON r.id=c.recipient_id
                JOIN news_reports report ON report.id=c.report_id
                WHERE c.token_hash=? AND c.expires_at>? AND r.active_yn='Y' AND report.deleted_at IS NULL
                """, (rs,n)->new Capability(rs.getLong("id"),rs.getLong("recipient_id"),
                json.readValue(rs.getString("snapshot_json"),Snapshot.class), time(rs,"expires_at")),hash,now).stream().findFirst();
    }
    public void lockCapability(long id) { jdbc.queryForObject("SELECT id FROM news_feedback_capabilities WHERE id=? FOR UPDATE", Long.class,id); }
    public void lockRecipient(long id) { jdbc.queryForObject("SELECT id FROM notification_recipients WHERE id=? FOR UPDATE",Long.class,id); }
    public List<Feedback> eventFeedback(long reportId) {
        return jdbc.query("SELECT * FROM news_feedback WHERE report_id=? AND event_key IS NOT NULL ORDER BY id",this::feedback,reportId);
    }
    /** List reads need review state only; diagnosis and frozen input CLOBs stay on single-report reads. */
    public List<Feedback> eventReviewStates(Collection<Long> reportIds) {
        return OracleInClause.batches(new LinkedHashSet<>(reportIds)).stream().flatMap(ids -> {
            String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
            return jdbc.query("""
                    SELECT id,capability_id,recipient_id,report_id,item_id,category,user_comment,
                      allow_personalization,request_hash,status,verdict,created_at,event_key
                    FROM news_feedback WHERE report_id IN (
                    """ + placeholders + ") AND event_key IS NOT NULL ORDER BY id", this::eventReviewState,
                    ids.toArray()).stream();
        }).sorted(Comparator.comparingLong(Feedback::id)).toList();
    }
    public Optional<Feedback> eventByRequest(long reportId,String key) {
        return jdbc.query("SELECT f.* FROM news_feedback f JOIN news_feedback_event_requests r ON r.feedback_id=f.id WHERE r.report_id=? AND r.idempotency_key=?",this::feedback,reportId,key).stream().findFirst();
    }
    public void reserveEventRequest(long reportId,String key,long feedbackId) {
        jdbc.update("INSERT INTO news_feedback_event_requests(report_id,idempotency_key,feedback_id) VALUES(?,?,?)",reportId,key,feedbackId);
    }
    public Optional<Feedback> eventByKey(long reportId,String key) {
        return jdbc.query("SELECT * FROM news_feedback WHERE report_id=? AND event_key=?",this::feedback,reportId,key).stream().findFirst();
    }
    public long submitEvent(long reportId,Item item,Category category,String comment,String key,String hash,LocalDateTime now) {
        return insert("""
                INSERT INTO news_feedback(report_id,event_key,category,user_comment,allow_personalization,
                  idempotency_key,request_hash,input_json,created_at) VALUES(?,?,?,?,'N',?,?,?,?)
                """,reportId,item.event().key(),category.name(),comment,key,hash,clob(item),now);
    }
    public Optional<Feedback> byItem(long recipientId,long reportId,long itemId) {
        return jdbc.query("SELECT * FROM news_feedback WHERE recipient_id=? AND report_id=? AND item_id=?",this::feedback,recipientId,reportId,itemId).stream().findFirst();
    }
    public Optional<Feedback> byRequest(long capabilityId,String key) {
        return jdbc.query("SELECT * FROM news_feedback WHERE capability_id=? AND idempotency_key=?",this::feedback,capabilityId,key).stream().findFirst();
    }
    public Optional<Feedback> byId(long id) { return jdbc.query("SELECT * FROM news_feedback WHERE id=?",this::feedback,id).stream().findFirst(); }
    public boolean hasItem(long capabilityId,long itemId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM news_feedback WHERE capability_id=? AND item_id=?",Integer.class,capabilityId,itemId)>0;
    }
    public List<Feedback> feedback(long recipientId,long reportId) {
        return jdbc.query("SELECT * FROM news_feedback WHERE recipient_id=? AND report_id=? ORDER BY id",this::feedback,recipientId,reportId);
    }
    public long submit(Capability capability, Item item, Category category,String comment,boolean consent,String key,String hash,LocalDateTime now) {
        return insert("""
                INSERT INTO news_feedback(capability_id,recipient_id,report_id,item_id,category,user_comment,
                  allow_personalization,idempotency_key,request_hash,input_json,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)
                """, capability.id(),capability.recipientId(),capability.snapshot().reportId(),item.itemId(),category.name(),
                comment,consent?"Y":"N",key,hash,clob(item),now);
    }
    private Feedback feedback(ResultSet rs,int row) throws SQLException {
        return new Feedback(rs.getLong("id"),rs.getObject("capability_id",Long.class),rs.getObject("recipient_id",Long.class),rs.getLong("report_id"),
                rs.getObject("item_id",Long.class),Category.valueOf(rs.getString("category")),rs.getString("user_comment"),
                "Y".equals(rs.getString("allow_personalization")),rs.getString("request_hash"),Status.valueOf(rs.getString("status")),
                rs.getString("verdict"),rs.getString("diagnosis"),time(rs,"created_at"),json.readValue(rs.getString("input_json"),Item.class),rs.getString("event_key"));
    }
    private Feedback eventReviewState(ResultSet rs,int row) throws SQLException {
        return new Feedback(rs.getLong("id"),rs.getObject("capability_id",Long.class),rs.getObject("recipient_id",Long.class),rs.getLong("report_id"),
                rs.getObject("item_id",Long.class),Category.valueOf(rs.getString("category")),rs.getString("user_comment"),
                "Y".equals(rs.getString("allow_personalization")),rs.getString("request_hash"),Status.valueOf(rs.getString("status")),
                rs.getString("verdict"),null,time(rs,"created_at"),null,rs.getString("event_key"));
    }
    public List<Policy> policies(long recipientId) {
        return jdbc.query("SELECT * FROM news_feedback_policies WHERE recipient_id=? ORDER BY id",this::policy,recipientId);
    }
    private Policy policy(ResultSet rs,int row) throws SQLException {
        return new Policy(rs.getLong("id"),rs.getLong("recipient_id"),rs.getLong("topic_id"),rs.getString("topic_name"),
                rs.getString("instruction"),rs.getInt("version"),rs.getString("status"),time(rs,"created_at"));
    }
    public void revoke(long id,long recipientId,int version,LocalDateTime now) {
        if(jdbc.update("UPDATE news_feedback_policies SET status='REVOKED',version=version+1,revoked_at=? WHERE id=? AND recipient_id=? AND version=? AND status='ACTIVE'",
                now,id,recipientId,version)!=1) throw FeedbackErrors.conflict();
    }
    public boolean activate(Feedback feedback,String instruction,LocalDateTime now) {
        // Serialize every recipient's writes, including different review jobs, before limit and duplicate checks.
        lockRecipient(feedback.recipientId());
        String hash=FeedbackTokens.hash(instruction.strip().toLowerCase(java.util.Locale.ROOT));
        List<Policy> same=policies(feedback.recipientId()).stream().filter(p->p.topicId()==feedback.input().topic().id()).toList();
        if(same.stream().anyMatch(p->p.status().equals("ACTIVE") && FeedbackTokens.hash(p.instruction().strip().toLowerCase(java.util.Locale.ROOT)).equals(hash))) return true;
        if(same.stream().filter(p->p.status().equals("ACTIVE")).count()>=20) return false;
        int version=same.stream().mapToInt(Policy::version).max().orElse(0)+1;
        insert("""
                INSERT INTO news_feedback_policies(recipient_id,topic_id,topic_name,instruction,instruction_hash,
                  version,source_feedback_id,created_at) VALUES(?,?,?,?,?,?,?,?)
                """,feedback.recipientId(),feedback.input().topic().id(),feedback.input().topic().name(),instruction,hash,version,feedback.id(),now);
        return true;
    }
    public void enqueue(String key,String kind,Long feedbackId,Long recipientId,long reportId,Long topicId,Object input,LocalDateTime now) {
        if(jdbc.queryForObject("SELECT COUNT(*) FROM news_feedback_jobs WHERE job_key=?",Integer.class,key)>0) return;
        try { insert("""
                INSERT INTO news_feedback_jobs(job_key,kind,feedback_id,recipient_id,report_id,topic_id,input_json,created_at,available_at)
                VALUES(?,?,?,?,?,?,?,?,?)
                """,key,kind,feedbackId,recipientId,reportId,topicId,clob(input),now,now); }
        catch(org.springframework.dao.DuplicateKeyException concurrent) { /* another event/poller won */ }
    }
    public List<Job> evaluations(long reportId,long recipientId) {
        return jdbc.query("SELECT * FROM news_feedback_jobs WHERE report_id=? AND recipient_id=? AND kind='EVALUATE'",this::job,reportId,recipientId);
    }
    public List<Long> pending(LocalDateTime now) {
        return jdbc.queryForList("SELECT id FROM news_feedback_jobs WHERE status='PENDING' AND available_at<=? ORDER BY id FETCH FIRST 2 ROWS ONLY",Long.class,now);
    }
    public Optional<Job> claim(long id,String key,LocalDateTime now) {
        if(jdbc.update("UPDATE news_feedback_jobs SET status='PROCESSING',claim_key=?,started_at=?,attempt_count=attempt_count+1 WHERE id=? AND status='PENDING' AND available_at<=? AND attempt_count<3",key,now,id,now)!=1) return Optional.empty();
        Job job=jdbc.queryForObject("SELECT * FROM news_feedback_jobs WHERE id=?",this::job,id);
        if(job.feedbackId()!=null) jdbc.update("UPDATE news_feedback SET status='PROCESSING' WHERE id=? AND status='PENDING'",job.feedbackId());
        return Optional.of(job);
    }
    private Job job(ResultSet rs,int row) throws SQLException {
        return new Job(rs.getLong("id"),rs.getString("kind"),rs.getObject("feedback_id",Long.class),
                rs.getObject("recipient_id",Long.class),rs.getLong("report_id"),rs.getObject("topic_id",Long.class),rs.getString("input_json"),
                rs.getString("result_json"),rs.getString("status"),rs.getString("claim_key"),rs.getInt("attempt_count"));
    }
    public boolean finish(Job job,Object result,LocalDateTime now) {
        return updateClob("UPDATE news_feedback_jobs SET status='COMPLETED',result_json=?,finished_at=? WHERE id=? AND status='PROCESSING' AND claim_key=?",
                json.writeValueAsString(result),now,job.id(),job.claimKey())==1;
    }
    public void reviewed(Feedback feedback,String verdict,String diagnosis,Object result,LocalDateTime now) {
        updateClob("UPDATE news_feedback SET review_json=?,status='COMPLETED',verdict=?,diagnosis=?,finished_at=? WHERE id=?",
                json.writeValueAsString(result),verdict,diagnosis,now,feedback.id());
    }
    public void fail(Job job,boolean retry,LocalDateTime now) {
        String status=retry && job.attempts()<3?"PENDING":"FAILED";
        if(jdbc.update("UPDATE news_feedback_jobs SET status=?,available_at=?,finished_at=?,claim_key=NULL WHERE id=? AND status='PROCESSING' AND claim_key=?",
                status,now.plusSeconds(30L*job.attempts()),status.equals("FAILED")?now:null,job.id(),job.claimKey())==1 && job.feedbackId()!=null)
            jdbc.update("UPDATE news_feedback SET status=? WHERE id=?",status,job.feedbackId());
    }
    public void expire(LocalDateTime now) {
        // A lost provider response is ambiguous. Do not repeat a paid call after the lease expires.
        jdbc.update("UPDATE news_feedback_jobs SET status='FAILED',finished_at=?,claim_key=NULL WHERE status='PROCESSING' AND started_at<?",now,now.minusMinutes(30));
        jdbc.update("UPDATE news_feedback SET status='FAILED' WHERE status='PROCESSING' AND EXISTS(SELECT 1 FROM news_feedback_jobs j WHERE j.feedback_id=news_feedback.id AND j.status='FAILED')");
    }
    public List<Feedback> export(long after,int limit) {
        return jdbc.query("SELECT * FROM news_feedback WHERE id>? AND status='COMPLETED' AND category<>'PREFERENCE' ORDER BY id FETCH FIRST "+limit+" ROWS ONLY",this::feedback,after);
    }
    private record Clob(String value) { }
    private Clob clob(Object value) { return new Clob(json.writeValueAsString(value)); }
    private long insert(String sql,Object... values) {
        GeneratedKeyHolder key=new GeneratedKeyHolder();
        jdbc.update(connection->{var statement=connection.prepareStatement(sql,new String[]{"ID"});
            for(int i=0;i<values.length;i++) set(statement,i+1,values[i]); return statement;},key);
        return java.util.Objects.requireNonNull(key.getKey()).longValue();
    }
    private int updateClob(String sql,String value,Object... remaining) {
        return jdbc.update(connection->{var statement=connection.prepareStatement(sql);
            set(statement,1,new Clob(value));for(int i=0;i<remaining.length;i++)set(statement,i+2,remaining[i]);return statement;});
    }
    private void set(java.sql.PreparedStatement statement,int index,Object value)throws SQLException {
        if(value instanceof Clob clob)statement.setCharacterStream(index,new StringReader(clob.value()),clob.value().length());
        else if(value instanceof LocalDateTime time)statement.setTimestamp(index,Timestamp.valueOf(time));
        else statement.setObject(index,value);
    }
    private static LocalDateTime time(ResultSet rs,String column)throws SQLException { return rs.getTimestamp(column).toLocalDateTime(); }
}
