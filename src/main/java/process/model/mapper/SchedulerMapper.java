package process.model.mapper;

import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import process.model.dto.SchedulerDto;
import process.model.pojo.Scheduler;

/**
 * @author Nabeel Ahmed
 */
@Component
@Scope("singleton")
public class SchedulerMapper implements Mapper<Scheduler, SchedulerDto> {

    /**
     * The method use to convert the scheduler to schedulerDto
     * @return SchedulerDto
     */
    @Override
    public SchedulerDto mapToDto(Scheduler scheduler) {
        SchedulerDto schedulerDto = new SchedulerDto();
        if (scheduler.getSchedulerId() != null) {
            schedulerDto.setSchedulerId(scheduler.getSchedulerId());
        }
        if (scheduler.getStartDate() != null) {
            schedulerDto.setStartDate(scheduler.getStartDate());
        }
        if (scheduler.getEndDate() != null) {
            schedulerDto.setEndDate(scheduler.getEndDate());
        }
        if (scheduler.getStartTime() != null) {
            schedulerDto.setStartTime(scheduler.getStartTime());
        }
        if (scheduler.getFrequency() != null) {
            schedulerDto.setFrequency(scheduler.getFrequency());
        }
        if (scheduler.getRecurrence() != null) {
            schedulerDto.setRecurrence(scheduler.getRecurrence());
        }
        if (scheduler.getJobId() != null) {
            schedulerDto.setJobId(scheduler.getJobId());
        }
        if (scheduler.getDateCreated() != null) {
            schedulerDto.setDateCreated(scheduler.getDateCreated());
        }
        if (scheduler.getRecurrenceTime() != null) {
            schedulerDto.setRecurrenceTime(scheduler.getRecurrenceTime());
        }
        return schedulerDto;
    }

    /**
     * The method use to convert the schedulerDto to scheduler
     * @return Scheduler
     */
    @Override
    public Scheduler mapToEntity(SchedulerDto schedulerDto) {
        Scheduler scheduler = new Scheduler();
        if (schedulerDto.getSchedulerId() != null) {
            scheduler.setSchedulerId(schedulerDto.getSchedulerId());
        }
        if (schedulerDto.getStartDate() != null) {
            scheduler.setStartDate(schedulerDto.getStartDate());
        }
        if (schedulerDto.getEndDate() != null) {
            scheduler.setEndDate(schedulerDto.getEndDate());
        }
        if (schedulerDto.getStartTime() != null) {
            scheduler.setStartTime(schedulerDto.getStartTime());
        }
        if (schedulerDto.getFrequency() != null) {
            scheduler.setFrequency(schedulerDto.getFrequency());
        }
        if (schedulerDto.getRecurrence() != null) {
            scheduler.setRecurrence(schedulerDto.getRecurrence());
        }
        if (schedulerDto.getJobId() != null) {
            scheduler.setJobId(schedulerDto.getJobId());
        }
        if (schedulerDto.getDateCreated() != null) {
            scheduler.setDateCreated(schedulerDto.getDateCreated());
        }
        if (schedulerDto.getRecurrenceTime() != null) {
            scheduler.setRecurrenceTime(schedulerDto.getRecurrenceTime());
        }
        return scheduler;
    }
}
