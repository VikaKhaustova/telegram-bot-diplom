package diplom.telegramBotDiplom.model;

import lombok.Data;

import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
@Data
@Entity(name = "dessertsTable")
public class Dessert  extends  Product{


    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private int id;
    private String name;
    private String description;
    private int weight;
    private String imageUrl;
    private double price;

    public Dessert() {
    }

    public Dessert(int id, String name,  String description,  int weight, String imageUrl, double price) {
        this.id = id;
        this.description = description;
        this.name = name;
        this.weight = weight;
        this.imageUrl = imageUrl;
        this.price = price;
    }
}
