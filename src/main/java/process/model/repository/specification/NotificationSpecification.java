package process.model.repository.specification;

import process.model.pojo.AppUser;
import process.model.pojo.NotificationAudit;
import process.util.ProcessUtil;
import process.util.lookuputil.APPLICATION_STATUS;
import java.util.Calendar;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * @author Nabeel Ahmed
 */
public class NotificationSpecification extends SearchSpecification<AppUser, NotificationAudit>  {

    private static final long serialVersionUID = 1L;

    private static final String DB_STATUS = "status";
    private static final String SEND_TO = "sendTo";
    private static final String EXPIRE_TIME = "expireTime";
    private Long days;

    /**
     * Method use to add user detail
     * @param  appUser
     * @param  days
     * */
    public NotificationSpecification(AppUser appUser, Long days) {
        super(appUser);
        this.days = days;
    }

    /**
     * Method use to create search pattern
     * @param root
     * @param  query
     * @param  criteriaBuilder
     * @param  appUser
     * */
    @Override
    public Predicate toPredicate(Root<NotificationAudit> root, CriteriaQuery<?> query,
        CriteriaBuilder criteriaBuilder, AppUser appUser) {
        List<Predicate> predicates = new ArrayList<>();
        if (!ProcessUtil.isNull(appUser.getUsername())) {
            predicates.add(criteriaBuilder.equal(root.get(SEND_TO), appUser));
        }
        predicates.add(criteriaBuilder.between(root.get(EXPIRE_TIME), new Timestamp(System.currentTimeMillis()),
            ProcessUtil.addDays(new Timestamp(System.currentTimeMillis()), this.days)));
        return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
    }

}