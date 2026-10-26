package com.example.be.domain.feedback.service;

import com.example.be.domain.feedback.repository.FeedbackStore;
import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

import static com.example.be.domain.feedback.model.FeedbackModels.*;

@Service
@RequiredArgsConstructor
public class FeedbackWorkService {
    private final FeedbackStore store;

    /** Registers only a requested delivery's recipient, independently of a pending delivery response. */
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void ensure(Snapshot snapshot,long recipientId) { enqueue(snapshot,recipientId,store.policies(recipientId)); }

    private void enqueue(Snapshot snapshot,long recipientId,List<Policy> all) {
        var scopes=all.stream().filter(p->p.recipientId()==recipientId && p.status().equals("ACTIVE")
                && !p.createdAt().isAfter(snapshot.generatedAt())).map(Policy::topicId).distinct().toList();
        LocalDateTime now=LocalDateTime.now(ApiTimeZone.ZONE);
        for(long topicId:scopes) {
            if(store.evaluations(snapshot.reportId(),recipientId).stream().anyMatch(j->j.topicId()==topicId))continue;
            List<Policy> policies=all.stream().filter(p->p.recipientId()==recipientId && p.topicId()==topicId && p.status().equals("ACTIVE") && !p.createdAt().isAfter(snapshot.generatedAt())).toList();
            Map<List<Long>,List<Item>> batches=new LinkedHashMap<>();
            for(Item item:snapshot.items()) {
                if(item.topic().id()!=topicId)continue;
                List<Long> ids=eligible(policies,item,snapshot.generatedAt()).stream().map(Policy::id).toList();
                if(!ids.isEmpty())batches.computeIfAbsent(ids,ignored->new ArrayList<>()).add(item);
            }
            int batch=0;
            for(var entry:batches.entrySet())for(int start=0;start<entry.getValue().size();start+=10) {
                var items=List.copyOf(entry.getValue().subList(start,Math.min(start+10,entry.getValue().size())));
                var selected=policies.stream().filter(p->entry.getKey().contains(p.id())).toList();
                store.enqueue("evaluate:"+snapshot.reportId()+":"+recipientId+":"+topicId+":"+(batch++),"EVALUATE",null,recipientId,snapshot.reportId(),topicId,
                        new EvaluationInput(items.getFirst().topic(),items,selected),now);
            }
            if(batch==0)store.enqueue("evaluate:"+snapshot.reportId()+":"+recipientId+":"+topicId+":empty","EVALUATE",null,recipientId,snapshot.reportId(),topicId,
                    new EvaluationInput(new Topic(topicId,policies.getFirst().topicName(),List.of(),List.of()),List.of(),List.of()),now);
        }
    }
    static List<Policy> eligible(List<Policy> policies,Item item,LocalDateTime generatedAt) {
        return policies.stream().filter(p->p.status().equals("ACTIVE") && p.topicId()==item.topic().id()
                && !p.createdAt().isAfter(generatedAt) && item.runStartedAt()!=null && item.runStartedAt().isAfter(p.createdAt())).toList();
    }
}
