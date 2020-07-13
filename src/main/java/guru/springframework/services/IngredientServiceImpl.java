package guru.springframework.services;

import guru.springframework.commands.IngredientCommand;
import guru.springframework.converters.IngredientCommandToIngredient;
import guru.springframework.converters.IngredientToIngredientCommand;
import guru.springframework.domain.Ingredient;
import guru.springframework.domain.Recipe;
import guru.springframework.repositories.reactive.RecipeReactiveRepository;
import guru.springframework.repositories.reactive.UnitOfMeasureReactiveRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Created by jt on 6/28/17.
 */
@Slf4j
@Service
public class IngredientServiceImpl implements IngredientService {

    private final IngredientToIngredientCommand ingredientToIngredientCommand;
    private final IngredientCommandToIngredient ingredientCommandToIngredient;
    private final RecipeReactiveRepository recipeReactiveRepository;
    private final UnitOfMeasureReactiveRepository unitOfMeasureReactiveRepository;

    public IngredientServiceImpl(IngredientToIngredientCommand ingredientToIngredientCommand,
                                 IngredientCommandToIngredient ingredientCommandToIngredient,
                                 RecipeReactiveRepository recipeReactiveRepository,
                                 UnitOfMeasureReactiveRepository unitOfMeasureReactiveRepository) {
        this.ingredientToIngredientCommand = ingredientToIngredientCommand;
        this.ingredientCommandToIngredient = ingredientCommandToIngredient;
        this.recipeReactiveRepository = recipeReactiveRepository;
        this.unitOfMeasureReactiveRepository = unitOfMeasureReactiveRepository;
    }

    @Override
    public Mono<IngredientCommand>  findByRecipeIdAndIngredientId(String recipeId, String ingredientId) {
        return recipeReactiveRepository
                .findById(recipeId)
                .flatMapIterable(Recipe::getIngredients)
                .filter(ingredient -> ingredient.getId().equalsIgnoreCase(ingredientId))
                .single()
                .map(ingredient -> {
                    IngredientCommand command = ingredientToIngredientCommand.convert(ingredient);
                    command.setRecipeId(recipeId);
                    return command;
                });
    }

    @Override
    public Mono<IngredientCommand> saveIngredientCommand(IngredientCommand command) {
        Objects.requireNonNull(command);
        AtomicReference<String> ingredientId = new AtomicReference<>();
        AtomicReference<String> recipeId = new AtomicReference<>();
        return recipeReactiveRepository.findById(command.getRecipeId())
                .map(recipe -> {
                    recipeId.set(recipe.getId());
                    recipe.getIngredients()
                            .stream()
                            .filter(ingredient -> ingredient.getId().equalsIgnoreCase(command.getId()))
                            .findFirst()
                            .map(ingredient -> {
                                ingredientId.set(command.getId());
                                ingredient.setDescription(command.getDescription());
                                ingredient.setAmount(command.getAmount());
                                return recipe;
                            })
                            .orElseGet(() -> {
                                Ingredient newIngredient = ingredientCommandToIngredient.convert(command);
                                ingredientId.set(Objects.requireNonNull(newIngredient).getId());
                                unitOfMeasureReactiveRepository
                                        .findById(command.getUom().getId())
                                        .flatMap(unitOfMeasure -> {
                                            newIngredient.setUom(unitOfMeasure);
                                            return Mono.just(unitOfMeasure);
                                        }).subscribe();
                                recipe.addIngredient(newIngredient);
                                return recipe;
                            });
                    return recipe;
                })
                .flatMap(recipe -> recipeReactiveRepository.save(recipe).then(Mono.just(recipe)))
                .flatMapIterable(Recipe::getIngredients)
                .filter(savedIngredient -> savedIngredient.getId().equalsIgnoreCase(ingredientId.get()))
                .flatMap(savedIngredient -> {
                    IngredientCommand ingredientCommand = ingredientToIngredientCommand.convert(savedIngredient);
                    ingredientCommand.setRecipeId(recipeId.get());
                    return Mono.justOrEmpty(ingredientCommand);
                })
                .single();
    }

    @Override
    public Mono<Void> deleteById(String recipeId, String idToDelete) {

        log.debug("Deleting ingredient: " + recipeId + ":" + idToDelete);

        Recipe recipe = recipeReactiveRepository.findById(recipeId).block();

        if(recipe != null){
            log.debug("found recipe");

            Optional<Ingredient> ingredientOptional = recipe
                    .getIngredients()
                    .stream()
                    .filter(ingredient -> ingredient.getId().equals(idToDelete))
                    .findFirst();

            if(ingredientOptional.isPresent()){
                log.debug("found Ingredient");
                recipe.getIngredients().remove(ingredientOptional.get());
                recipeReactiveRepository.save(recipe).block();
            }
        } else {
            log.debug("Recipe Id Not found. Id:" + recipeId);
        }
        return Mono.empty();
    }
}
