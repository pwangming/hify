package com.hify.demo.controller;

import com.hify.common.Result;
import com.hify.common.page.PageResult;
import com.hify.demo.dto.CreateDemoItemRequest;
import com.hify.demo.dto.DemoItemResponse;
import com.hify.demo.dto.UpdateDemoItemRequest;
import com.hify.demo.service.DemoItemService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * DemoItem 的 REST 接口（RESTful 资源设计，api-standards.md 第 2 节）。
 *
 * <p>协议转换层：只做参数校验（{@code @Valid}）、调用本模块 service、组装 {@code Result} 返回；
 * 不写业务逻辑、不写 try-catch、不写 {@code @Transactional}、不注入 Mapper（code-organization.md 第 2 节）。
 * 失败一律由 service 抛 {@code BizException}，全局异常处理器统一转信封。
 */
@RestController
@RequestMapping("/api/v1/demo-items")
public class DemoItemController {

    private final DemoItemService demoItemService;

    public DemoItemController(DemoItemService demoItemService) {
        this.demoItemService = demoItemService;
    }

    @PostMapping
    public Result<DemoItemResponse> create(@Valid @RequestBody CreateDemoItemRequest request) {
        return Result.ok(demoItemService.create(request));
    }

    @PutMapping("/{id}")
    public Result<DemoItemResponse> update(@PathVariable Long id,
                                           @Valid @RequestBody UpdateDemoItemRequest request) {
        return Result.ok(demoItemService.update(id, request));
    }

    @GetMapping("/{id}")
    public Result<DemoItemResponse> get(@PathVariable Long id) {
        return Result.ok(demoItemService.get(id));
    }

    /** 页码分页（api-standards.md 第 3.1 节）：入参 page（默认 1）、size（默认 20）。 */
    @GetMapping
    public Result<PageResult<DemoItemResponse>> page(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.ok(demoItemService.page(page, size));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        demoItemService.delete(id);
        return Result.ok(null);
    }
}
