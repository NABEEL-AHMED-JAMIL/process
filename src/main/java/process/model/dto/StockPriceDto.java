package process.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import java.util.Date;

/**
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StockPriceDto {

    private String id;
    private Date date;
    private Double openPrice;
    private Double highPrice;
    private Double lowPrice;
    private Double closePrice;
    private Double wap;
    private Integer noOfShares;
    private Integer noOfTrades;
    private Double totalTurnover;
    private Integer deliverableQuantity;
    private Double deliQtyToTradedQty;
    private Double spreadHighLow;
    private Double spreadCloseOpen;

    public StockPriceDto() {}

    public String getId() {
        return id;
    }
    public void setId(String id) {
        this.id = id;
    }

    public Date getDate() { return date; }
    public void setDate(Date date) { this.date = date; }

    public Double getOpenPrice() { return openPrice; }
    public void setOpenPrice(Double openPrice) { this.openPrice = openPrice; }

    public Double getHighPrice() { return highPrice; }
    public void setHighPrice(Double highPrice) { this.highPrice = highPrice; }

    public Double getLowPrice() { return lowPrice; }
    public void setLowPrice(Double lowPrice) { this.lowPrice = lowPrice; }

    public Double getClosePrice() { return closePrice; }
    public void setClosePrice(Double closePrice) { this.closePrice = closePrice; }

    public Double getWap() { return wap; }
    public void setWap(Double wap) { this.wap = wap; }

    public Integer getNoOfShares() { return noOfShares; }
    public void setNoOfShares(Integer noOfShares) { this.noOfShares = noOfShares; }

    public Integer getNoOfTrades() { return noOfTrades; }
    public void setNoOfTrades(Integer noOfTrades) { this.noOfTrades = noOfTrades; }

    public Double getTotalTurnover() { return totalTurnover; }
    public void setTotalTurnover(Double totalTurnover) { this.totalTurnover = totalTurnover; }

    public Integer getDeliverableQuantity() { return deliverableQuantity; }
    public void setDeliverableQuantity(Integer deliverableQuantity) { this.deliverableQuantity = deliverableQuantity; }

    public Double getDeliQtyToTradedQty() { return deliQtyToTradedQty; }
    public void setDeliQtyToTradedQty(Double deliQtyToTradedQty) { this.deliQtyToTradedQty = deliQtyToTradedQty; }

    public Double getSpreadHighLow() { return spreadHighLow; }
    public void setSpreadHighLow(Double spreadHighLow) { this.spreadHighLow = spreadHighLow; }

    public Double getSpreadCloseOpen() { return spreadCloseOpen; }
    public void setSpreadCloseOpen(Double spreadCloseOpen) { this.spreadCloseOpen = spreadCloseOpen; }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
