package diplom.telegramBotDiplom.model;

import lombok.Data;

import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;

@Data
@Entity(name = "additionsTable")
public class Additions  extends  Product {


    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private int id;
    private String name;
    private int weight;
    private String imageUrl;
    private double price;

    public Additions() {
    }

    public Additions(int id, String name, int weight, String imageUrl, double price) {
        this.id = id;
        this.name = name;
        this.weight = weight;
        this.imageUrl = imageUrl;
        this.price = price;
    }
}
