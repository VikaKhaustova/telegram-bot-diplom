package diplom.telegramBotDiplom.model;

import lombok.Data;

import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;

@Data
@Entity(name = "pizzaTable")
public class Pizza  extends  Product {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private int id;
    private String name;
    private String description;
    private String imageUrl;
    private String ingredients;
    private double price;

    public Pizza() {
    }

    public Pizza(int id, String name, String description, String imageUrl, String ingredients, double price) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.imageUrl = imageUrl;
        this.ingredients = ingredients;
        this.price = price;
    }
}
