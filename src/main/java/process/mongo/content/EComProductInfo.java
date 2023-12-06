package process.mongo.content;

import com.google.gson.Gson;
import java.util.Set;

/**
 * @author Nabeel Ahmed
 * DarProductInfo
 * */
public class EComProductInfo {

	private String id;
	private String category;
	private String subCategory;
	private String product;
	private String title;
	private String description;
	private String price;
	private String actualPrice;
	private String discount;
	private Set<String> color;
	private Set<String> size;
	private Set<String> sizeType;
	private String brand;
	private String rating;
	private String saleBy;
	private String sellerRating;
	private String shipOnTimeRating;
	private String chatResponseRate;

	public EComProductInfo() {
	}

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getCategory() {
		return category;
	}

	public void setCategory(String category) {
		this.category = category;
	}

	public String getSubCategory() {
		return subCategory;
	}

	public void setSubCategory(String subCategory) {
		this.subCategory = subCategory;
	}

	public String getProduct() {
		return product;
	}

	public void setProduct(String product) {
		this.product = product;
	}

	public String getTitle() {
		return title;
	}

	public void setTitle(String title) {
		this.title = title;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public String getPrice() {
		return price;
	}

	public void setPrice(String price) {
		this.price = price;
	}

	public String getActualPrice() {
		return actualPrice;
	}

	public void setActualPrice(String actualPrice) {
		this.actualPrice = actualPrice;
	}

	public String getDiscount() {
		return discount;
	}

	public void setDiscount(String discount) {
		this.discount = discount;
	}

	public Set<String> getColor() {
		return color;
	}

	public void setColor(Set<String> color) {
		this.color = color;
	}

	public Set<String> getSize() {
		return size;
	}

	public void setSize(Set<String> size) {
		this.size = size;
	}

	public Set<String> getSizeType() {
		return sizeType;
	}

	public void setSizeType(Set<String> sizeType) {
		this.sizeType = sizeType;
	}

	public String getBrand() {
		return brand;
	}

	public void setBrand(String brand) {
		this.brand = brand;
	}

	public String getRating() {
		return rating;
	}

	public void setRating(String rating) {
		this.rating = rating;
	}

	public String getSaleBy() {
		return saleBy;
	}

	public void setSaleBy(String saleBy) {
		this.saleBy = saleBy;
	}

	public String getSellerRating() {
		return sellerRating;
	}

	public void setSellerRating(String sellerRating) {
		this.sellerRating = sellerRating;
	}

	public String getShipOnTimeRating() {
		return shipOnTimeRating;
	}

	public void setShipOnTimeRating(String shipOnTimeRating) {
		this.shipOnTimeRating = shipOnTimeRating;
	}

	public String getChatResponseRate() {
		return chatResponseRate;
	}

	public void setChatResponseRate(String chatResponseRate) {
		this.chatResponseRate = chatResponseRate;
	}

	@Override
	public String toString() {
		return new Gson().toJson(this);
	}

}
