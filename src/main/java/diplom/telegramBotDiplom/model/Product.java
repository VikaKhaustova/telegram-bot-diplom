package diplom.telegramBotDiplom.model;

import lombok.Data;

import javax.persistence.*;
@Data
@Entity
@Inheritance(strategy = InheritanceType.TABLE_PER_CLASS)
public abstract class Product {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private int id;

    private String name;
    private double price;
    private int quantity;


}


